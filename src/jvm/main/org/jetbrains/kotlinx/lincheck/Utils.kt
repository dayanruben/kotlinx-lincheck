/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.util.*
import sun.nio.ch.lincheck.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.*
import java.lang.reflect.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

fun <T> List<T>.isSuffixOf(list: List<T>): Boolean {
    if (size > list.size) return false
    for (i in indices) {
       if (this[size - i - 1] != list[list.size - i - 1]) return false
    }
    return true
}

fun chooseSequentialSpecification(sequentialSpecificationByUser: Class<*>?, testClass: Class<*>): Class<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass
    else sequentialSpecificationByUser

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    try {
        val m = getMethod(instance, actor.method)
        val args = (if (actor.isSuspendable) actor.arguments + completion else actor.arguments)
        val res = m.invoke(instance, *args.toTypedArray())
        return if (m.returnType.isAssignableFrom(Void.TYPE)) VoidResult else createLincheckResult(res)
    } catch (invE: Throwable) {
        // If the exception is thrown not during the method invocation - fail immediately
        if (invE !is InvocationTargetException)
            throw invE
        // Exception thrown not during the method invocation should contain underlying exception
        return ExceptionResult.create(
            invE.cause?.takeIf { !isInternalException(it) }
                ?: throw invE
        )
    } catch (e: Exception) {
        e.catch(
            NoSuchMethodException::class.java,
            IllegalAccessException::class.java
        ) {
            throw IllegalStateException("Cannot invoke method " + actor.method, e)
        }
    }
}

private val methodsCache = WeakHashMap<Class<*>, WeakHashMap<Method, WeakReference<Method>>>()

/**
 * Get the same [method] for [instance] solving the different class loaders problem.
 */
@Synchronized
internal fun getMethod(instance: Any, method: Method): Method {
    val methods = methodsCache.computeIfAbsent(instance.javaClass) { WeakHashMap() }
    return methods[method]?.get() ?: run {
        val m = instance.javaClass.getMethod(method.name, method.parameterTypes)
        methods[method] = WeakReference(m)
        m
    }
}

/**
 * Finds a method with the specified [name] and (parameters)[parameterTypes]
 * ignoring the difference in class loaders for these parameter types.
 */
private fun Class<out Any>.getMethod(name: String, parameterTypes: Array<Class<out Any>>): Method =
    methods.find { method ->
        method.name == name && method.parameterTypes.map { it.name } == parameterTypes.map { it.name }
    } ?: throw NoSuchMethodException("${getName()}.$name(${parameterTypes.joinToString(",")})")

/**
 * @return hashcode of the unboxed value if [value] represents a boxed primitive, otherwise returns [System.identityHashCode]
 * of the [value].
 */
internal fun primitiveOrIdentityHashCode(value: Any?): Int {
    return if (value.isPrimitive) value.hashCode() else System.identityHashCode(value)
}

/**
 * Creates [Result] of corresponding type from any given value.
 *
 * Java [Void] and Kotlin [Unit] classes are represented as [VoidResult].
 *
 * Instances of [Throwable] are represented as [ExceptionResult].
 *
 * The special [COROUTINE_SUSPENDED] value returned when some coroutine suspended its execution
 * is represented as [NoResult].
 *
 * Success values of [kotlin.Result] instances are represented as either [VoidResult] or [ValueResult].
 * Failure values of [kotlin.Result] instances are represented as [ExceptionResult].
 */
internal fun createLincheckResult(res: Any?) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> VoidResult
    res != null && res is Throwable -> ExceptionResult.create(res)
    res === COROUTINE_SUSPENDED -> Suspended
    res is kotlin.Result<Any?> -> res.toLinCheckResult()
    else -> ValueResult(res)
}

private fun kotlin.Result<Any?>.toLinCheckResult() =
    if (isSuccess) {
        when (val value = getOrNull()) {
            is Unit -> VoidResult
            else -> ValueResult(value)
        }
    } else ExceptionResult.create(exceptionOrNull()!!)

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

internal class StoreExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler
{
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null
    val cancelled = runOutsideIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor()) {
        cancel(cancellationByLincheckException)
    }
    exceptionHandler.exception?.let {
        throw it.cause!! // let's throw the original exception, ignoring the internal coroutines details
    }
    return when {
        cancelled -> CancellationResult.CANCELLED_BEFORE_RESUMPTION
        promptCancellation -> {
            context[Job]!!.cancel() // we should always put a job into the context for prompt cancellation
            CancellationResult.CANCELLED_AFTER_RESUMPTION
        }
        else -> CancellationResult.CANCELLATION_FAILED
    }
}

internal enum class CancellationResult { CANCELLED_BEFORE_RESUMPTION, CANCELLED_AFTER_RESUMPTION, CANCELLATION_FAILED }

/**
 * Returns `true` if the continuation was cancelled by [CancellableContinuation.cancel].
 */
fun <T> kotlin.Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal fun collectThreadDump(runner: Runner) = Thread.getAllStackTraces().filter { (t, _) ->
    t is TestThread && runner.isCurrentRunnerThread(t)
}

internal val String.canonicalClassName get() = this.replace('/', '.')

internal val Throwable.text: String get() {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.buffer.toString()
}

/**
 * Returns all found fields in the hierarchy.
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
internal val Class<*>.allDeclaredFieldWithSuperclasses get(): List<Field> {
    val fields: MutableList<Field> = ArrayList<Field>()
    var currentClass: Class<*>? = this
    while (currentClass != null) {
        val declaredFields: Array<Field> = currentClass.declaredFields
        fields.addAll(declaredFields)
        currentClass = currentClass.superclass
    }
    return fields
}

/**
 * Finds a public/protected/private/internal field in the class and its superclasses/interfaces by name.
 *
 * @param fieldName the name of the field to find.
 * @return the [java.lang.reflect.Field] object if found, or `null` if not found.
 */
internal fun Class<*>.findField(fieldName: String): Field {
    // Search in the class hierarchy
    var clazz: Class<*>? = this
    while (clazz != null) {
        // Check class itself
        try {
            return clazz.getDeclaredField(fieldName)
        }
        catch (_: NoSuchFieldException) {}

        // Check interfaces
        for (interfaceClass in clazz.interfaces) {
            try {
                return interfaceClass.getDeclaredField(fieldName)
            }
            catch (_: NoSuchFieldException) {}
        }

        // Move up the hierarchy
        clazz = clazz.superclass
    }

    throw NoSuchFieldException("Class '${this.name}' does not have field '$fieldName'")
}

/**
 * Thrown in case when `cause` exception is unexpected by Lincheck internal logic.
 */
internal class LincheckInternalBugException(cause: Throwable): Exception(cause)

// We use receivers for `runInIgnoredSection` to not use these functions
// accidentally instead of `invokeInIgnoredSection` in the transformation logic.

@Suppress("UnusedReceiverParameter")
internal inline fun<R> FixedActiveThreadsExecutor.runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> ParallelThreadsRunner.runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> LincheckClassFileTransformer.runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> EventTracker.runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> ExecutionClassLoader.runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

internal inline fun <R> runInIgnoredSection(descriptor: ThreadDescriptor?, block: () -> R): R {
    if (descriptor == null || descriptor.eventTracker !is ManagedStrategy)
        return block()
    if (descriptor.inIgnoredSection()) {
        return block()
    }
    descriptor.enterIgnoredSection().ensureTrue()
    return try {
        block()
    } finally {
        descriptor.leaveIgnoredSection()
    }
}

@Suppress("UnusedReceiverParameter")
internal inline fun <R> ParallelThreadsRunner.runOutsideIgnoredSection(block: () -> R) =
    runOutsideIgnoredSection(ThreadDescriptor.getCurrentThreadDescriptor(), block)

/**
 * Exits the ignored section and invokes the provided [block] outside the ignored section,
 * entering the ignored section back after the [block] is executed.
 * This method **must** be called in an ignored section.
 */
internal inline fun <R> runOutsideIgnoredSection(descriptor: ThreadDescriptor?, block: () -> R): R {
    if (descriptor == null || descriptor.eventTracker !is ManagedStrategy)
        return block()
    check(descriptor.inIgnoredSection()) {
        "Current thread must be in ignored section"
    }
    descriptor.leaveIgnoredSection()
    return try {
        block()
    } finally {
        descriptor.enterIgnoredSection().ensureTrue()
    }
}

internal const val LINCHECK_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck."
internal const val LINCHECK_RUNNER_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck.runner."

internal fun <T> Class<T>.newDefaultInstance(): T {
    @Suppress("UNCHECKED_CAST")
    val constructor = this.declaredConstructors.singleOrNull { it.parameterCount == 0 } as? Constructor<T>
    if (constructor != null) {
        return constructor.newInstance()
    }

    if (this.enclosingClass != null) {
        val enclosingObject = this.enclosingClass.newDefaultInstance()
        return this.getDeclaredConstructor(this.enclosingClass)
            .also { it.isAccessible = true }
            .newInstance(enclosingObject)
    }

    throw IllegalStateException("No suitable constructor found for ${this.canonicalName}")
}

// We store the class references in a local map to avoid repeated Class.forName calls and reflection overhead
val classCache = ConcurrentHashMap<String, Class<*>>()
