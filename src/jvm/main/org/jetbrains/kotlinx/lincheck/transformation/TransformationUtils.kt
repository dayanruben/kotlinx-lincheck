/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import sun.nio.ch.lincheck.Injections
import org.objectweb.asm.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.Opcodes.ANEWARRAY
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import java.io.*
import java.util.*
import java.util.concurrent.*
import java.lang.invoke.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*


/**
 * Generates bytecode to push a null value onto the stack.
 *
 * Before execution:
 * STACK: <empty>
 *
 * After execution:
 * STACK: null
 */
internal fun GeneratorAdapter.pushNull() {
    visitInsn(Opcodes.ACONST_NULL)
}

/**
 * Copies the top value of the stack to a local variable and reloads it onto the stack.
 *
 * @param local Index of the local variable.
 */
internal fun GeneratorAdapter.copyLocal(local: Int) {
    storeLocal(local)
    loadLocal(local)
}

/**
 * Loads all local variables into the stack.
 *
 * @param locals Array of local variables.
 * @param valueTypes If not-null, denotes an array of types of values that should be put onto stack.
 *   The type of the local value can be the same as the type of the actual value on the stack,
 *   or it can be its boxed variant.
 */
internal fun GeneratorAdapter.loadLocals(locals: IntArray, valueTypes: Array<Type>? = null) {
    locals.forEachIndexed { i, local ->
        loadLocal(local)
        if (valueTypes != null) {
            val valueType = valueTypes[i]
            val localType = getLocalType(local)
            if (valueType != localType) {
                check(localType == OBJECT_TYPE)
                if (valueType.requiresBoxing) {
                    unbox(valueType)
                }
            }
        }
    }
}

/**
 * Stores N top values from the stack in the local variables.
 *
 * Before execution:
 * STACK: value_1, value_2, ... value_n
 *
 * After execution:
 * STACK: <empty>
 *
 * @param valueTypes List of types of values to be stored.
 * @param localTypes If passed, denotes the desired types of local variables.
 *   The type of the local value can be the same as the type of the actual value on the stack,
 *   or it can be its boxed variant.
 * @return Array of local variables containing arguments.
 */
internal fun GeneratorAdapter.storeLocals(
    valueTypes: Array<Type>,
    localTypes: Array<Type> = valueTypes
): IntArray {
    val locals = IntArray(valueTypes.size)
    // Store values in reverse order
    for (i in valueTypes.indices.reversed()) {
        val valueType = valueTypes[i]
        val localType = localTypes[i]
        locals[i] = newLocal(localType)
        if (valueType != localType) {
            check(localType == OBJECT_TYPE)
            if (valueType.requiresBoxing) {
                box(valueType)
            }
        }
        storeLocal(locals[i], localType)
    }
    return locals
}

/**
 * Duplicates the value on the top of the stack.
 * 
 * Before execution:
 * STACK: x
 *
 * After execution:
 * STACK: x, x
 *
 * @param type The type of the value to duplicate. Determines the duplication behavior.
 *             For VOID_TYPE, no operation is performed.
 *             For LONG_TYPE or DOUBLE_TYPE, dup2 is used to duplicate a 64-bit value.
 *             For all other types, a standard dup is performed.
 */
internal fun GeneratorAdapter.dup(type: Type) {
    when (type) {
        VOID_TYPE -> {}
        LONG_TYPE, DOUBLE_TYPE -> dup2()
        else -> dup()
    }
}

/**
 * Copies N top values from the stack in the local variables.
 *
 * Before execution:
 * STACK: param_1, param_2, ... param_n
 *
 * After execution:
 * STACK: param_1, param_2, ... param_n
 *
 * @param valueTypes List of types of values to be stored.
 * @param localTypes If passed, denotes the desired types of local variables.
 *   The type of the local value can be the same as the type of the actual value on the stack,
 *   or it can be its boxed variant.
 * @return Array of local variables containing arguments.
 */
internal fun GeneratorAdapter.copyLocals(
    valueTypes: Array<Type>,
    localTypes: Array<Type> = valueTypes
): IntArray {
    check(valueTypes.size == localTypes.size)
    val locals = storeLocals(valueTypes, localTypes)
    locals.forEachIndexed { i, local ->
        val valueType = valueTypes[i]
        val localType = localTypes[i]
        loadLocal(local)
        if (valueType != localType) {
            check(localType == OBJECT_TYPE)
            if (valueType.requiresBoxing) {
                unbox(valueType)
            }
        }
    }
    return locals
}

/**
 * Stores arguments of the method in the local variables.
 *
 * Before execution:
 * STACK: param_1, param_2, ... param_n
 *
 * After execution:
 * STACK: (empty)
 *
 * @param methodDescriptor String representation of the method's descriptor.
 * @return Array of local variables containing arguments.
 */
internal fun GeneratorAdapter.storeArguments(methodDescriptor: String): IntArray {
    val argumentTypes = getArgumentTypes(methodDescriptor)
    return storeLocals(argumentTypes)
}

/**
 * Executes a try-catch-finally block within the context of the GeneratorAdapter.
 * 
 * Attention: this method does not insert `finally` blocks before inner return and throw statements.
 *
 * @param tryBlock The code block to be executed in the try section.
 * @param exceptionType The type of exception to be caught in the `catch` section, or null to catch all exceptions.
 * @param catchBlock The code block to be executed in the `catch` section if an exception is thrown.
 * By default, it re-throws the exception.
 * When called, it expects the exception to be on the top of the stack.
 * @param finallyBlock The code block to be executed in the `finally` section. This is optional.
 */
internal fun GeneratorAdapter.tryCatchFinally(
    tryBlock: GeneratorAdapter.() -> Unit,
    exceptionType: Type? = null,
    catchBlock: (GeneratorAdapter.() -> Unit)? = null,
    finallyBlock: (GeneratorAdapter.() -> Unit)? = null,
) {
    val startTryBlockLabel = newLabel()
    val endTryBlockLabel = newLabel()
    val exceptionHandlerLabel = newLabel()
    val endLabel = newLabel()
    visitTryCatchBlock(
        startTryBlockLabel,
        endTryBlockLabel,
        exceptionHandlerLabel,
        exceptionType?.internalName
    )
    visitLabel(startTryBlockLabel)
    tryBlock()
    visitLabel(endTryBlockLabel)
    if (finallyBlock != null) finallyBlock()
    goTo(endLabel)
    visitLabel(exceptionHandlerLabel)
    if (catchBlock != null) {
        if (finallyBlock != null) {
            tryCatchFinally(
                tryBlock = catchBlock,
                catchBlock = null,
                finallyBlock = finallyBlock,
            )
        } else {
            catchBlock()
        }
    } else {
        if (finallyBlock != null) finallyBlock()
        throwException()
    }
    visitLabel(endLabel)
}

/**
 * Copies arguments of the method in the local variables.
 *
 * Before execution:
 * STACK: param_1, param_2, ... param_n
 *
 * After execution:
 * STACK: param_1, param_2, ... param_n
 *
 * @param methodDescriptor String representation of the method's descriptor.
 * @return Array of local variables containing arguments.
 */
internal fun GeneratorAdapter.copyArguments(methodDescriptor: String): IntArray {
    val argumentTypes = getArgumentTypes(methodDescriptor)
    return copyLocals(argumentTypes)
}

/**
 * Pushes onto the stack an array consisting of values stored in the local variables.
 *
 * Before execution:
 * STACK: (empty)
 *
 * After execution:
 * STACK: array
 *
 * @param locals Local variables which values are stored in the stack.
 */
internal fun GeneratorAdapter.pushArray(locals: IntArray) {
    // STACK: <empty>
    push(locals.size)
    // STACK: arraySize
    visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
    // STACK: array
    for (i in locals.indices) {
        // STACK: array
        dup()
        // STACK: array, array
        push(i)
        // STACK: array, array, index
        loadLocal(locals[i])
        // STACK: array, array, index, value[index]
        box(getLocalType(locals[i]))
        arrayStore(OBJECT_TYPE)
        // STACK: array
    }
    // STACK: array
}

private val Type.requiresBoxing: Boolean
    get() = !(sort == OBJECT || sort == ARRAY)

/**
 * Adds invocation of [beforeEvent] method.
 * This method **must** be called from the user code, as [beforeEvent] must be called from the user code due to the contract
 * between the Lincheck IDEA plugin and Lincheck.
 *
 * @param setMethodEventId a flag that identifies that method call event id set is required
 */
internal fun GeneratorAdapter.invokeBeforeEvent(debugMessage: String, setMethodEventId: Boolean) = invokeInIgnoredSection {
    ifStatement(
        condition = {
            invokeStatic(Injections::shouldInvokeBeforeEvent)
        },
        thenClause = {
            if (setMethodEventId) {
                invokeStatic(Injections::setLastMethodCallEventId)
            }
            push(debugMessage)
            invokeStatic(Injections::getNextEventId)
            dup()
            ifStatement(
                condition = {
                    invokeStatic(Injections::isBeforeEventRequested)
                },
                thenClause = {
                    push(debugMessage)
                    invokeStatic(Injections::beforeEvent)
                },
                elseClause = {
                    pop()
                }
            )
        },
        elseClause = {}
    )
}

// Map for storing the declaring class and method of each function.
internal val functionToDeclaringClassMap = HashMap<KFunction<*>, Pair<Type, Method>>()

/**
 * Invokes a static method represented by a KFunction.
 */
internal fun GeneratorAdapter.invokeStatic(function: KFunction<*>) {
    val (clazz, method) = functionToDeclaringClassMap.computeIfAbsent(function) {
        function.javaMethod!!.let {
            getType(it.declaringClass) to Method.getMethod(it)
        }
    }
    invokeStatic(clazz, method)
}

/**
 * Generates an if-statement in bytecode.
 *
 * @param condition the condition code.
 * @param thenClause the then-clause code.
 * @param elseClause the else-clause code.
 */
internal inline fun GeneratorAdapter.ifStatement(
    condition: GeneratorAdapter.() -> Unit,
    thenClause: GeneratorAdapter.() -> Unit,
    elseClause: GeneratorAdapter.() -> Unit = { },
) {
    val ifClauseStart = newLabel()
    val end = newLabel()
    condition()
    ifZCmp(GeneratorAdapter.GT, ifClauseStart)
    elseClause()
    goTo(end)
    visitLabel(ifClauseStart)
    thenClause()
    visitLabel(end)
}

/**
 * Generates an if-then-else statement, testing if the current execution point
 * is currently inside a Lincheck tested section
 * (i.e., not inside the ignored section of the analysis).
 * If so, the [original] bytecode sequence will be executed, otherwise
 * the instrumented [code] will be executed.
 *
 * @param original the original code.
 * @param code the code to execute in the Lincheck's testing context.
 */
internal inline fun GeneratorAdapter.invokeIfInTestingCode(
    original: GeneratorAdapter.() -> Unit,
    code: GeneratorAdapter.() -> Unit
) {
    ifStatement(
        condition = { invokeStatic(Injections::inIgnoredSection) },
        thenClause = original,
        elseClause = code
    )
}

/**
 * Generates a bytecode sequence to execute a given block of bytecode within an ignored section,
 * ensuring that any ignored sections are properly entered and exited.
 *
 * @param code A block of bytecode to be executed inside the ignored section.
 */
internal inline fun GeneratorAdapter.invokeInIgnoredSection(
    code: GeneratorAdapter.() -> Unit
) {
    invokeStatic(Injections::enterIgnoredSection)
    val enteredIgnoredSection = newLocal(BOOLEAN_TYPE)
    storeLocal(enteredIgnoredSection)
    code()
    ifStatement(
        condition = {
            loadLocal(enteredIgnoredSection)
        },
        thenClause = {
            invokeStatic(Injections::leaveIgnoredSection)
        },
        elseClause = {}
    )
}

/**
 * @param type asm type descriptor.
 * @return whether [type] is a java array type (primitive or reference).
 */
internal fun isArray(type: Type): Boolean = type.sort == Type.ARRAY

/**
 * @param type asm type descriptor.
 * @return whether [type] is a non-reference primitive type (e.g. `int`, `boolean`, etc.).
 */
internal fun isPrimitive(type: Type): Boolean {
    return when (type.sort) {
        Type.BOOLEAN, Type.CHAR, Type.BYTE,
        Type.SHORT, Type.INT, Type.FLOAT,
        Type.LONG, Type.DOUBLE, Type.VOID -> true
        else -> false
    }
}

private val isThreadSubclassMap = ConcurrentHashMap<String, Boolean>()

internal fun isThreadSubClass(internalClassName: String): Boolean {
    if (internalClassName == JAVA_THREAD_CLASSNAME) return true
    return isThreadSubclassMap.computeIfAbsent(internalClassName) {
        isSubClassOf(internalClassName, JAVA_THREAD_CLASSNAME)
    }
}

private val isCoroutineStateMachineClassMap = ConcurrentHashMap<String, Boolean>()

internal fun isCoroutineStateMachineClass(internalClassName: String): Boolean {
    if (internalClassName.startsWith("java/")) return false
    if (internalClassName.startsWith("kotlin/") && !internalClassName.startsWith("kotlin/coroutines/")) return false
    return isCoroutineStateMachineClassMap.computeIfAbsent(internalClassName) {
        getSuperclassName(internalClassName) == "kotlin/coroutines/jvm/internal/ContinuationImpl"
    }
}

internal fun isCoroutineInternalClass(internalClassName: String): Boolean =
    internalClassName == "kotlin/coroutines/intrinsics/IntrinsicsKt" ||
    internalClassName == "kotlinx/coroutines/internal/StackTraceRecoveryKt"

private fun getSuperclassName(internalClassName: String): String? {
    class SuperclassClassVisitor : ClassVisitor(ASM_API) {
        var internalSuperclassName: String? = null
            private set

        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
            internalSuperclassName = superName
        }
    }
    try {
        val classStream: InputStream = ClassLoader.getSystemClassLoader()
            .getResourceAsStream("$internalClassName.class")
            ?: return null
        val classReader = ClassReader(classStream)
        val superclassVisitor = SuperclassClassVisitor()
        classReader.accept(superclassVisitor, 0)
        return superclassVisitor.internalSuperclassName
    } catch (t: Throwable) {
        // Failed to read or process the class.
        return null
    }
}

private fun isSubClassOf(internalClassName: String, internalSuperClassName: String): Boolean {
    if (internalClassName == internalSuperClassName) return true
    val superclassName = getSuperclassName(internalClassName)
        ?: return false
    return isSubClassOf(superclassName, internalSuperClassName)
}

/**
 * Tests if the provided [className] contains `"ClassLoader"` as a substring.
 */
internal fun containsClassloaderInName(className: String): Boolean =
    className.contains("ClassLoader")

/**
 * Determines if a given class name represents a method handle related class,
 * that is one of the following classes:
 *   - [MethodHandle]
 *   - [MethodHandles]
 *   - [MethodHandles.Lookup]
 *   - [MethodType]
 */
internal fun isMethodHandleRelatedClass(className: String): Boolean =
    className.startsWith("java.lang.invoke") &&
    (className.contains("MethodHandle") || className.contains("MethodType"))

/**
 * Determines whether the specified [MethodHandle] method should be ignored.
 *
 * We ignore all methods from [MethodHandle], except various `invoke` methods, such as:
 *   - [MethodHandle.invoke]
 *   - [MethodHandle.invokeExact]
 *   - [MethodHandle.invokeWithArguments]
 * These methods are not ignored because we need to analyze the invoked target method.
 */
internal fun isIgnoredMethodHandleMethod(className: String, methodName: String): Boolean =
    isMethodHandleRelatedClass(className) && !methodName.contains("invoke")

/**
 * Tests if the provided [className] represents [StackTraceElement] class.
 */
internal fun isStackTraceElementClass(className: String): Boolean =
    className == "java.lang.StackTraceElement"

/**
 * Tests if the provided [className] represents one of jdk internal [ThreadContainer] classes
 * that use [JavaLangAccess.start] API to start threads.
 */
internal fun isThreadContainerClass(className: String): Boolean =
    className == "jdk.internal.vm.SharedThreadContainer"  ||
    className == "jdk.internal.misc.ThreadFlock"

/**
 * Tests if the provided [className] represents an internal coroutine dispatcher class.
 */
internal fun isCoroutineDispatcherInternalClass(className: String): Boolean =
    className.startsWith("kotlinx.coroutines.internal") && className.contains("DispatchedContinuation")

internal const val ASM_API = Opcodes.ASM9

internal val STRING_TYPE = getType(String::class.java)
internal val CLASS_TYPE = getType(Class::class.java)

internal val CLASS_FOR_NAME_METHOD = Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE))

internal const val JAVA_THREAD_CLASSNAME = "java/lang/Thread"
