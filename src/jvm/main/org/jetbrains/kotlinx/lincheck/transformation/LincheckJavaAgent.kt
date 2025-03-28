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

import net.bytebuddy.agent.ByteBuddyAgent
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.runInIgnoredSection
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.MODEL_CHECKING
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.STRESS
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.isEagerlyInstrumentedClass
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.shouldTransform
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.transformedClassesStress
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.INSTRUMENT_ALL_CLASSES
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentedClasses
import org.jetbrains.kotlinx.lincheck.transformation.transformers.LocalVariableInfo
import org.jetbrains.kotlinx.lincheck.util.Logger
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import sun.misc.Unsafe
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.reflect.Modifier
import java.security.ProtectionDomain
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile


/**
 * Executes [block] with the Lincheck java agent for byte-code instrumentation.
 */
internal inline fun withLincheckJavaAgent(instrumentationMode: InstrumentationMode, block: () -> Unit) {
    LincheckJavaAgent.install(instrumentationMode)
    return try {
        block()
    } finally {
        LincheckJavaAgent.uninstall()
    }
}

internal enum class InstrumentationMode {
    /**
     * In this mode, Lincheck transforms bytecode
     * only to track coroutine suspensions.
     */
    STRESS,

    /**
     * In this mode, Lincheck tracks
     * all shared memory manipulations.
     */
    MODEL_CHECKING
}

/**
 * LincheckJavaAgent represents the Lincheck Java agent responsible for instrumenting bytecode.
 *
 * @property instrumentation The ByteBuddy instrumentation instance.
 * @property instrumentationMode The instrumentation mode to determine which classes to transform.
 */
internal object LincheckJavaAgent {
    /**
     * The [Instrumentation] instance is used to perform bytecode transformations during runtime.
     */
    private val instrumentation = ByteBuddyAgent.install()

    /**
     * Determines how to transform classes;
     * see [InstrumentationMode.STRESS] and [InstrumentationMode.MODEL_CHECKING].
     */
    lateinit var instrumentationMode: InstrumentationMode

    /**
     * Indicates whether the "bootstrap.jar" (see the "bootstrap" project module)
     * is added to the bootstrap class loader classpath.
     * See [install] for details.
     */
    private var isBootstrapJarAddedToClasspath = false

    /**
     * Names (canonical) of the classes that were instrumented since the last agent installation.
     */
    val instrumentedClasses = HashSet<String>()

    /**
     * Dynamically attaches [LincheckClassFileTransformer] to this JVM instance.
     * Please note that the dynamic attach feature will be disabled in future JVM releases,
     * but at the moment of implementing this logic (March 2024), it was the smoothest way
     * to inject code in the user codebase when the `java.base` module also needs to be instrumented.
     */
    fun install(instrumentationMode: InstrumentationMode) {
        this.instrumentationMode = instrumentationMode
        // The bytecode injections must be loaded with the bootstrap class loader,
        // as the `java.base` module is loaded with it. To achieve that, we pack the
        // classes related to the bytecode injections in a separate JAR (see the
        // "bootstrap" project module), and add it to the bootstrap classpath.
        if (!isBootstrapJarAddedToClasspath) { // don't do this twice.
            appendBootstrapJarToClassLoaderSearch()
            isBootstrapJarAddedToClasspath = true
        }
        // Add the Lincheck bytecode transformer to this JVM instance,
        // allowing already loaded classes re-transformation.
        instrumentation.addTransformer(LincheckClassFileTransformer, true)
        // The transformation logic depends on the testing strategy.
        when {
            // If an option to enable transformation of all classes is explicitly set,
            // then we re-transform all the classes
            // (this option is used for testing purposes).
            INSTRUMENT_ALL_CLASSES -> {
                // Re-transform the already loaded classes.
                // New classes will be transformed automatically.
                instrumentation.retransformClasses(*getLoadedClassesToInstrument().toTypedArray())
            }

            // In the stress testing mode, Lincheck needs to track coroutine suspensions.
            // As an optimization, we remember the set of loaded classes that actually
            // have suspension points, so later we can re-transform only those classes.
            instrumentationMode == STRESS -> {
                check(instrumentedClasses.isEmpty())
                val classes = getLoadedClassesToInstrument().filter {
                    val canonicalClassName = it.name
                    // new classes that were loaded after the latest STRESS mode re-transformation
                    !transformedClassesStress.containsKey(canonicalClassName) ||
                    // old classes that were already loaded before and have coroutine method calls inside
                    canonicalClassName in coroutineCallingClasses
                }
                // for some reason, without `isNotEmpty()` check this code can throw NPE on JVM 8
                if (classes.isNotEmpty()) {
                    instrumentation.retransformClasses(*classes.toTypedArray())
                    instrumentedClasses.addAll(classes.map { it.name })
                }
            }

            // In the model checking mode, Lincheck processes classes lazily, only when they are used.
            instrumentationMode == MODEL_CHECKING -> {
                check(instrumentedClasses.isEmpty())
                // Transform some predefined classes eagerly on start,
                // because often it's the only place when we can do it
                val eagerlyTransformedClasses = getLoadedClassesToInstrument()
                    .filter { isEagerlyInstrumentedClass(it.name) }
                    .toTypedArray()
                instrumentation.retransformClasses(*eagerlyTransformedClasses)
                instrumentedClasses.addAll(eagerlyTransformedClasses.map { it.name })
            }
        }
    }

    private fun appendBootstrapJarToClassLoaderSearch() {
        // The "bootstrap" module is packed to "bootstrap.jar",
        // which is in this JAR resources. We need to append this
        // "bootstrap.jar" to the bootstrap class loader classpath.
        // However, it is impossible to instantiate a `File` instance
        // that leads to a file inside a JAR archive. Therefore,
        // we copy "bootstrap.jar" to a temporary file, adding this
        // temporary file to the bootstrap class loader classpath later on.
        val bootstrapJarAsStream = this.javaClass.getResourceAsStream("/bootstrap.jar")
        val tempBootstrapJarFile = File.createTempFile("lincheck-bootstrap", ".jar")
        bootstrapJarAsStream.use { input ->
            tempBootstrapJarFile.outputStream().use { fileOut ->
                input!!.copyTo(fileOut)
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(JarFile(tempBootstrapJarFile))
    }

    private fun getLoadedClassesToInstrument(): List<Class<*>> =
        instrumentation.allLoadedClasses
            .filter(instrumentation::isModifiableClass)
            .filter { shouldTransform(it.name, instrumentationMode) }

    /**
     * Detaches [LincheckClassFileTransformer] from this JVM instance and re-transforms
     * the transformed classes to remove the Lincheck injections.
     */
    fun uninstall() {
        // Remove the Lincheck transformer.
        instrumentation.removeTransformer(LincheckClassFileTransformer)
        // Collect the set of instrumented classes.
        val classes = if (INSTRUMENT_ALL_CLASSES)
            getLoadedClassesToInstrument()
        else
            getLoadedClassesToInstrument()
            // Skip classes not transformed by Lincheck.
            .filter { clazz ->
                val canonicalClassName = clazz.name
                canonicalClassName in instrumentedClasses
            }
        // `retransformClasses` uses initial (loaded in VM from disk) class bytecode and reapplies
        // transformations of all agents that did not remove their transformers to this moment;
        // for some reason, without `isNotEmpty()` check this code can throw NPE on JVM 8
        if (classes.isNotEmpty()) {
            instrumentation.retransformClasses(*classes.toTypedArray())
        }
        // Clear the set of instrumented classes.
        instrumentedClasses.clear()
    }

    /**
     * Ensures that the specified class and all its superclasses are transformed.
     *
     * This function is called before creating a new instance of the specified class
     * or reading a static field of it. It ensures that the whole hierarchy of this class
     * and the classes of all the static fields (this process is recursive) is transformed.
     * Notably, some of these classes may not be loaded yet, and invoking the `<cinit>`
     * during the analysis could cause non-deterministic behaviour (the class initialization
     * is invoked only once, while Lincheck relies on the events reproducibility).
     * To eliminate the issue, this function also loads the class before transformation,
     * thus, initializing it here, in an ignored section of the analysis, re-transforming
     * the class after that.
     *
     * @param canonicalClassName The name of the class to be transformed.
     */
    fun ensureClassHierarchyIsTransformed(canonicalClassName: String) {
        if (INSTRUMENT_ALL_CLASSES) {
            Class.forName(canonicalClassName)
            return
        }
        if (!shouldTransform(canonicalClassName, instrumentationMode)) return
        if (canonicalClassName in instrumentedClasses) return // already instrumented
        ensureClassHierarchyIsTransformed(Class.forName(canonicalClassName), Collections.newSetFromMap(IdentityHashMap()))
    }


    /**
     * Ensures that the given object and all its referenced objects are transformed for Lincheck analysis.
     * If the INSTRUMENT_ALL_CLASSES_IN_MODEL_CHECKING_MODE flag is set to true, no transformation is performed.
     *
     * The function is called upon a test instance creation, to ensure that all the classes related to it are transformed.
     *
     * @param testInstance the object to be transformed
     */
    fun ensureObjectIsTransformed(testInstance: Any) {
        if (INSTRUMENT_ALL_CLASSES) {
            return
        }
        ensureObjectIsTransformed(testInstance, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * Ensures that the given class and all its superclasses are transformed if necessary.
     *
     * @param clazz the class to transform
     */
    private fun ensureClassHierarchyIsTransformed(clazz: Class<*>) {
        if (INSTRUMENT_ALL_CLASSES) {
            return
        }
        if (clazz.name in instrumentedClasses) return // already instrumented
        ensureClassHierarchyIsTransformed(clazz, Collections.newSetFromMap(IdentityHashMap()))
    }

    /**
     * Ensures that the given object and all its referenced objects are transformed according to the provided rules.
     * The transformation is performed recursively, starting from the given object.
     *
     * @param obj The object to be ensured for transformation.
     * @param processedObjects A set of processed objects to avoid infinite recursion.
     */
    private fun ensureObjectIsTransformed(obj: Any, processedObjects: MutableSet<Any>) {
        if (obj is Array<*>) {
            obj.filterNotNull().forEach { ensureObjectIsTransformed(it, processedObjects) }
            return
        }

        if (!instrumentation.isModifiableClass(obj.javaClass) || !shouldTransform(obj.javaClass.name, instrumentationMode)) {
            return
        }

        if (processedObjects.contains(obj)) return
        processedObjects += obj

        var clazz: Class<*> = obj.javaClass

        ensureClassHierarchyIsTransformed(clazz)

        while (true) {
            clazz.declaredFields
                .filter { !it.type.isPrimitive }
                .filter { !Modifier.isStatic(it.modifiers) }
                .mapNotNull { readFieldViaUnsafe(obj, it, Unsafe::getObject) }
                .forEach {
                    ensureObjectIsTransformed(it, processedObjects)
                }
            clazz = clazz.superclass ?: break
        }
    }

    /**
     * Ensures that the given class and all its superclasses are transformed.
     *
     * @param clazz The class to be transformed.
     * @param processedObjects Set of objects that have already been processed to prevent duplicate transformation.
     */
    private fun ensureClassHierarchyIsTransformed(clazz: Class<*>, processedObjects: MutableSet<Any>) {
        if (instrumentation.isModifiableClass(clazz) && shouldTransform(clazz.name, instrumentationMode)) {
            instrumentedClasses += clazz.name
            instrumentation.retransformClasses(clazz)
        } else {
            return
        }
        // Traverse static fields.
        clazz.declaredFields
            .filter { !it.type.isPrimitive }
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { readFieldViaUnsafe(null, it, Unsafe::getObject) }
            .forEach {
                ensureObjectIsTransformed(it, processedObjects)
            }
        clazz.superclass?.let {
            if (it.name in instrumentedClasses) return // already instrumented
            ensureClassHierarchyIsTransformed(it, processedObjects)
        }
    }

    /**
     * FOR TEST PURPOSE ONLY!
     * To test the byte-code transformation correctness, we can transform all classes.
     *
     * Both stress and model checking modes implement some optimizations
     * to avoid re-transforming all loaded into VM classes on each run of a Lincheck test.
     * When this flag is set, these optimizations are disabled, and so
     * the Lincheck agent re-transforms all the loaded classes on each run.
     */
    internal val INSTRUMENT_ALL_CLASSES =
        System.getProperty("lincheck.instrumentAllClasses")?.toBoolean() ?: false
}

internal object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    val transformedClassesModelChecking = ConcurrentHashMap<String, ByteArray>()
    val transformedClassesStress = ConcurrentHashMap<String, ByteArray>()

    private val transformedClassesCache
        get() = when (instrumentationMode) {
            STRESS -> transformedClassesStress
            MODEL_CHECKING -> transformedClassesModelChecking
        }

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? = runInIgnoredSection {
        if (classBeingRedefined != null) {
            require(internalClassName != null) {
                "Internal class name of redefined class ${classBeingRedefined.name} must not be null"
            }
        }
        // Internal class name could be `null` in some cases (can be witnessed on JDK-8),
        // this can be related to the Kotlin compiler bug:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        if (internalClassName == null) return null
        // If the class should not be transformed, return immediately.
        if (!shouldTransform(internalClassName.canonicalClassName, instrumentationMode)) {
            return null
        }
        // In the model checking mode, we transform classes lazily,
        // once they are used in the testing code.
        if (!INSTRUMENT_ALL_CLASSES &&
            instrumentationMode == MODEL_CHECKING &&
            // do not re-transform already instrumented classes
            internalClassName.canonicalClassName !in instrumentedClasses &&
            // always transform eagerly instrumented classes
            !isEagerlyInstrumentedClass(internalClassName.canonicalClassName)) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    private fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray = transformedClassesCache.computeIfAbsent(internalClassName.canonicalClassName) {
        Logger.debug { "Transforming $internalClassName" }

        val reader = ClassReader(classBytes)

        // the following code is required for local variables access tracking
        val classNode = ClassNode()
        reader.accept(classNode, 0)
        val methods = mapMethodsToLabels(classNode)

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        val visitor = LincheckClassVisitor(writer, instrumentationMode, methods)
        try {
            reader.accept(visitor, ClassReader.EXPAND_FRAMES)
            writer.toByteArray()
        } catch (e: Throwable) {
            System.err.println("Unable to transform $internalClassName")
            e.printStackTrace()
            classBytes
        }
    }

    private fun mapMethodsToLabels(
        classNode: ClassNode
    ): Map<String, Map<Int, List<LocalVariableInfo>>> {
        return classNode.methods.associateBy(
            keySelector = { m -> m.name + m.desc },
            valueTransform = { m ->
                mutableMapOf<Int, MutableList<LocalVariableInfo>>().also { map ->
                    m.localVariables?.forEach { local ->
                        val index = local.index
                        val type = Type.getType(local.desc)
                        val info = LocalVariableInfo(local.name, local.start.label to local.end.label, type)
                        map.getOrPut(index) { mutableListOf() }.add(info)
                    }
                }
            }
        )
    }

    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String, instrumentationMode: InstrumentationMode): Boolean {
        // In the stress testing mode, we can simply skip the standard
        // Java and Kotlin classes -- they do not have coroutine suspension points.
        if (instrumentationMode == STRESS) {
            if (className.startsWith("java.") || className.startsWith("kotlin.")) return false
        }
        // We should transform all eagerly instrumented classes.
        if (isEagerlyInstrumentedClass(className)) {
            return true
        }
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            if (className == "java.lang.Thread") return true
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.")) return true
            if (className.startsWith("com.sun.")) return false
            return false
        }
        if (className.startsWith("sun.")) return false
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) {
            // Transform `ThreadContainer.start` to detect thread forking.
            if (isThreadContainerClass(className)) return true
            return false
        }
        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            return false
        }
        // We do not instrument AtomicFU atomics.
        if (className.startsWith("kotlinx.atomicfu.")) {
            if (className.contains("Atomic")) return false
            return true
        }
        // We need to skip the classes related to the debugger support in Kotlin coroutines.
        if (className.startsWith("kotlinx.coroutines.debug.")) return false
        if (className == "kotlinx.coroutines.DebugKt") return false
        // We should never transform the coverage-related classes.
        if (className.startsWith("com.intellij.rt.coverage.")) return false
        // We can also safely do not instrument some libraries for performance reasons.
        if (className.startsWith("com.esotericsoftware.kryo.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("net.rubygrapefruit.platform.")) return false
        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("it.unimi.dsi.fastutil.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("org.slf4j.")) return false
        if (className.startsWith("org.apache.commons.lang.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        // Finally, we should never instrument the Lincheck classes.
        if (className.startsWith("org.jetbrains.kotlinx.lincheck.")) return false
        if (className.startsWith("sun.nio.ch.lincheck.")) return false
        // All the classes that were not filtered out are eligible for transformation.
        return true
    }

    // We should always eagerly transform the following classes.
    internal fun isEagerlyInstrumentedClass(className: String): Boolean =
        // `ClassLoader` classes, to wrap `loadClass` methods in the ignored section.
        containsClassloaderInName(className) ||
        // `MethodHandle` class, to wrap its methods (except `invoke` methods) in the ignored section.
        isMethodHandleRelatedClass(className) ||
        // `StackTraceElement` class, to wrap all its methods into the ignored section.
        isStackTraceElementClass(className) ||
        // `ThreadContainer` classes, to detect threads started in the thread containers.
        isThreadContainerClass(className) ||
        // TODO: instead of eagerly instrumenting `DispatchedContinuation`
        //  we should try to fix lazy class re-transformation logic
        isCoroutineDispatcherInternalClass(className)
}
