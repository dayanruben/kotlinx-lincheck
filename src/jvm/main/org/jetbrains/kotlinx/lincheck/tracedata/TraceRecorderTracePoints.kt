/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

import org.jetbrains.kotlinx.lincheck.transformation.isJavaLambdaClass
import java.io.DataInput
import java.io.DataOutput
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

private val EVENT_ID_GENERATOR = AtomicInteger(0)

var INJECTIONS_VOID_OBJECT: Any? = null

sealed class TRTracePoint(
    val codeLocationId: Int,
    val threadId: Int,
    val eventId: Int
) {
   open fun save(out: DataOutput) {
        out.writeByte(getClassId(this))
        out.writeInt(codeLocationId)
        out.writeInt(threadId)
        out.writeInt(eventId)
    }

    val codeLocation: StackTraceElement get() = CodeLocations.stackTrace(codeLocationId)

    abstract fun toText(verbose: Boolean): String
}

class TRMethodCallTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    var result: TRObject? = null
    var exceptionClassName: String? = null
    @Transient
    val events: MutableList<TRTracePoint> = mutableListOf()

    val methodDescriptor: MethodDescriptor get() = TRACE_CONTEXT.getMethodDescriptor(methodId)

    // Shortcuts
    val className: String get() = methodDescriptor.className
    val methodName: String get() = methodDescriptor.methodName
    val argumentTypes: List<Types.Type> get() = methodDescriptor.argumentTypes
    val returnType: Types.Type get() = methodDescriptor.returnType

    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(methodId)
        out.writeTRObject(obj)
        out.writeInt(parameters.size)
        parameters.forEach {
            out.writeTRObject(it)
        }
        out.writeTRObject(result)
        out.writeUTF(exceptionClassName ?: "")
        out.writeInt(events.size)
        events.forEach {
            it.save(out)
        }
    }

    override fun toText(verbose: Boolean): String {
        val md = TRACE_CONTEXT.getMethodDescriptor(methodId)
        val sb = StringBuilder()
        if (obj != null) {
            sb.append(obj.adornedRepresentation())
        } else {
            sb.append(md.className.substringAfterLast("."))
        }
        sb.append('.')
            .append(md.methodName)
            .append('(')

        parameters.forEachIndexed { i, it ->
            if (i != 0) {
                sb.append(", ")
            }
            sb.append(it.toString())
        }
        sb.append(')')
        if (exceptionClassName != null) {
            sb.append(": threw ")
            sb.append(exceptionClassName)
        } else if (result != TR_OBJECT_VOID) {
            sb.append(": ")
            sb.append(result.toString())
        }
        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        const val id = 0

        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRMethodCallTracePoint {
            val methodId = inp.readInt()
            val obj = inp.readTRObject()
            val pcount = inp.readInt()
            val parameters = mutableListOf<TRObject?>()
            repeat(pcount) {
                parameters.add(inp.readTRObject())
            }

            val tracePoint = TRMethodCallTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                methodId = methodId,
                obj = obj,
                parameters = parameters,
                eventId = eventId,
            )

            tracePoint.result = inp.readTRObject()
            tracePoint.exceptionClassName = inp.readUTF()
            if (tracePoint.exceptionClassName?.isEmpty() ?: false) {
                tracePoint.exceptionClassName = null
            }

            val ecount = inp.readInt()
            repeat(ecount) {
                tracePoint.events.add(loadTRTracePoint(inp))
            }

            return tracePoint
        }
    }
}

sealed class TRFieldTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(codeLocationId, threadId, eventId) {
    protected abstract val directionSymbol: String

    val fieldDescriptor: FieldDescriptor get() = TRACE_CONTEXT.getFieldDescriptor(fieldId)

    // Shortcuts
    val className: String get() = fieldDescriptor.className
    val name: String get() = fieldDescriptor.fieldName
    val isStatic: Boolean get() = fieldDescriptor.isStatic
    val isFinal: Boolean get() = fieldDescriptor.isFinal

    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val fd = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        val sb = StringBuilder()
        if (obj != null) {
            sb.append(obj.adornedRepresentation())
        } else {
            sb.append(fd.className.substringAfterLast("."))
        }
        sb.append('.')
            .append(fd.fieldName)
            .append(directionSymbol)
            .append(value.toString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }
}

class TRReadTracePoint(
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRObject?,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(threadId, codeLocationId,  fieldId, obj, value, eventId) {
    override val directionSymbol: String get() = " → "

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadTracePoint {
            return TRReadTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRWriteTracePoint(
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRObject?,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(threadId, codeLocationId,  fieldId, obj, value, eventId) {
    override val directionSymbol: String get() = " ← "

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteTracePoint {
            return TRWriteTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

sealed class TRLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(codeLocationId, threadId, eventId) {
    protected abstract val directionSymbol: String

    val variableDescriptor: VariableDescriptor get() = TRACE_CONTEXT.getVariableDescriptor(localVariableId)
    val name: String get() = variableDescriptor.name

    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val vd = TRACE_CONTEXT.getVariableDescriptor(localVariableId)
        val sb = StringBuilder()
        sb.append(vd.name)
            .append(directionSymbol)
            .append(value.toString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }
}

class TRReadLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(threadId, codeLocationId, localVariableId, value, eventId) {
    override val directionSymbol: String get() = " → "

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadLocalVariableTracePoint {
            return TRReadLocalVariableTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRWriteLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(threadId, codeLocationId, localVariableId, value, eventId) {
    override val directionSymbol: String get() = " ← "

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteLocalVariableTracePoint {
            return TRWriteLocalVariableTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

sealed class TRArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject,
    val index: Int,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(codeLocationId, threadId, eventId) {
    protected abstract val directionSymbol: String

    override fun save(out: DataOutput) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val sb = StringBuilder()
        sb.append(array.className.substringAfterLast("."))
            .append('@')
            .append(array.identityHashCode)
            .append('[')
            .append(index)
            .append("]")
            .append(directionSymbol)
            .append(value.toString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }
}

class TRReadArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    array: TRObject,
    index: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(threadId, codeLocationId, array, index, value, eventId) {
    override val directionSymbol: String get() = " → "

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadArrayTracePoint {
            return TRReadArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject() ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRWriteArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    array: TRObject,
    index: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(threadId, codeLocationId, array, index, value, eventId) {
    override val directionSymbol: String get() = " ← "

    override fun toText(verbose: Boolean): String {
        val sb = StringBuilder()
        sb.append(array.className.substringAfterLast("."))
            .append('@')
            .append(array.identityHashCode)
            .append('[')
            .append(index)
            .append("] ← ")
            .append(value.toString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteArrayTracePoint {
            return TRWriteArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject() ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

fun loadTRTracePoint(inp: DataInput): TRTracePoint {
    val loader = getLoaderByClassId(inp.readByte())
    val codeLocationId = inp.readInt()
    val threadId = inp.readInt()
    val eventId = inp.readInt()
    return loader(inp, codeLocationId, threadId, eventId)
}

@ConsistentCopyVisibility
data class TRObject internal constructor (
    internal val classNameId: Int,
    val identityHashCode: Int,
    internal val primitiveValue: Any?
) {
    val className: String  get() = primitiveValue?.javaClass?.name ?: TRACE_CONTEXT.getClassDescriptor(classNameId).name
    val isPrimitive: Boolean get() = primitiveValue != null
    val value: Any? get() = primitiveValue

    // TODO: Unify with code like `ObjectLabelFactory.adornedStringRepresentation` placed in hypothetical `core` module
    override fun toString(): String {
        return if (primitiveValue != null) {
            when (primitiveValue) {
                is String -> {
                    if (classNameId == TR_OBJECT_P_RAW_STRING) return primitiveValue
                    // Escape special characters
                    val v = primitiveValue
                        .replace("\\", "\\\\")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                    return "\"$v\""
                }
                is Char -> "'$primitiveValue'"
                is Unit -> "Unit"
                else -> primitiveValue.toString()
            }
        } else if (classNameId == TR_OBJECT_NULL_CLASSNAME) {
            "null"
        } else if (classNameId == TR_OBJECT_VOID_CLASSNAME) {
            "void"
        } else {
            adornedRepresentation()
        }
    }

    fun adornedRepresentation(): String =
        adornedClassNameRepresentation(className) + "@" + identityHashCode

    private fun adornedClassNameRepresentation(className: String): String =
        className.substringAfterLast(".").let {
            if (isJavaLambdaClass(it)) it.substringBeforeLast('/')
            else it
        }
}

private const val TR_OBJECT_NULL_CLASSNAME = -1
val TR_OBJECT_NULL = TRObject(TR_OBJECT_NULL_CLASSNAME, 0, null)

private const val TR_OBJECT_VOID_CLASSNAME = -2
val TR_OBJECT_VOID = TRObject(TR_OBJECT_VOID_CLASSNAME, 0, null)

private const val TR_OBJECT_P_BYTE = TR_OBJECT_VOID_CLASSNAME - 1
private const val TR_OBJECT_P_SHORT = TR_OBJECT_P_BYTE - 1
private const val TR_OBJECT_P_INT = TR_OBJECT_P_SHORT - 1
private const val TR_OBJECT_P_LONG = TR_OBJECT_P_INT - 1
private const val TR_OBJECT_P_FLOAT = TR_OBJECT_P_LONG - 1
private const val TR_OBJECT_P_DOUBLE = TR_OBJECT_P_FLOAT - 1
private const val TR_OBJECT_P_CHAR = TR_OBJECT_P_DOUBLE - 1
private const val TR_OBJECT_P_STRING = TR_OBJECT_P_CHAR - 1
private const val TR_OBJECT_P_UNIT = TR_OBJECT_P_STRING - 1
private const val TR_OBJECT_P_RAW_STRING = TR_OBJECT_P_UNIT - 1
private const val TR_OBJECT_P_BOOLEAN = TR_OBJECT_P_RAW_STRING - 1

fun TRObjectOrNull(obj: Any?): TRObject? =
    obj?.let { TRObject(it) }

fun TRObjectOrVoid(obj: Any?): TRObject? =
    if (obj == INJECTIONS_VOID_OBJECT) TR_OBJECT_VOID
    else TRObjectOrNull(obj)

fun TRObject(obj: Any): TRObject {
    return when (obj) {
        is Byte -> TRObject(TR_OBJECT_P_BYTE, 0, obj)
        is Short -> TRObject(TR_OBJECT_P_SHORT, 0, obj)
        is Int -> TRObject(TR_OBJECT_P_INT, 0, obj)
        is Long -> TRObject(TR_OBJECT_P_LONG, 0, obj)
        is Float -> TRObject(TR_OBJECT_P_FLOAT, 0, obj)
        is Double -> TRObject(TR_OBJECT_P_DOUBLE, 0, obj)
        is Char -> TRObject(TR_OBJECT_P_CHAR, 0, obj)
        is String -> TRObject(TR_OBJECT_P_STRING, 0, obj)
        is CharSequence -> TRObject(TR_OBJECT_P_STRING, 0, obj.toString())
        is Unit -> TRObject(TR_OBJECT_P_UNIT, 0, obj)
        is Boolean -> TRObject(TR_OBJECT_P_BOOLEAN, 0, obj)
        // Render these types to strings for simplicity
        is Enum<*> -> TRObject(TR_OBJECT_P_RAW_STRING, 0, "${obj.javaClass.simpleName}.${obj.name}")
        is BigInteger -> TRObject(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        is BigDecimal -> TRObject(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        // Generic case
        else -> TRObject(TRACE_CONTEXT.getOrCreateClassId(obj.javaClass.name), System.identityHashCode(obj), null)
    }
}

private fun DataOutput.writeTRObject(value: TRObject?) {
    // null
    if (value == null) {
        writeInt(TR_OBJECT_NULL_CLASSNAME)
        return
    }
    // Negatives are special markers
    writeInt(value.classNameId)
    if (value.classNameId >= 0) {
        writeInt(value.identityHashCode)
        return
    }
    if (value.classNameId > TR_OBJECT_P_BYTE) {
        return
    }
    when (value.primitiveValue) {
        is Byte -> writeByte(value.primitiveValue.toInt())
        is Short -> writeShort(value.primitiveValue.toInt())
        is Int -> writeInt(value.primitiveValue)
        is Long -> writeLong(value.primitiveValue)
        is Float -> writeFloat(value.primitiveValue)
        is Double -> writeDouble(value.primitiveValue)
        is Char -> writeChar(value.primitiveValue.code)
        is String -> writeUTF(value.primitiveValue) // Both STRING and RAW_STRING
        is Boolean -> writeBoolean(value.primitiveValue)
        is Unit -> {}
        else -> error("Unknow primitive value ${value.primitiveValue}")
    }
}

private fun DataInput.readTRObject(): TRObject? {
    return when (val classNameId = readInt()) {
        TR_OBJECT_NULL_CLASSNAME -> null
        TR_OBJECT_VOID_CLASSNAME -> TR_OBJECT_VOID
        TR_OBJECT_P_BYTE -> TRObject(classNameId, 0, readByte())
        TR_OBJECT_P_SHORT -> TRObject(classNameId, 0, readShort())
        TR_OBJECT_P_INT -> TRObject(classNameId, 0, readInt())
        TR_OBJECT_P_LONG -> TRObject(classNameId, 0, readLong())
        TR_OBJECT_P_FLOAT -> TRObject(classNameId, 0, readFloat())
        TR_OBJECT_P_DOUBLE -> TRObject(classNameId, 0, readDouble())
        TR_OBJECT_P_CHAR -> TRObject(classNameId, 0, readChar())
        TR_OBJECT_P_STRING -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_UNIT -> TRObject(classNameId, 0, Unit)
        TR_OBJECT_P_RAW_STRING -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_BOOLEAN -> TRObject(classNameId, 0, readBoolean())
        else -> {
            if (classNameId >= 0) {
                TRObject(classNameId, readInt(), null)
            } else {
                error("TRObject: Unknown Class Id $classNameId")
            }
        }
    }
}

private fun <V: Appendable> V.append(codeLocationId: Int, verbose: Boolean): V {
    if (!verbose) return this
    val cl = CodeLocations.stackTrace(codeLocationId)
    append(" at ").append(cl.fileName).append(':').append(cl.lineNumber.toString())
    return this
}

private typealias TRLoader = (DataInput, Int, Int, Int) -> TRTracePoint

private fun getClassId(point: TRTracePoint): Int {
    return when (point) {
        is TRMethodCallTracePoint -> 0
        is TRReadArrayTracePoint -> 1
        is TRReadLocalVariableTracePoint -> 2
        is TRReadTracePoint -> 3
        is TRWriteArrayTracePoint -> 4
        is TRWriteLocalVariableTracePoint -> 5
        is TRWriteTracePoint -> 6
    }
}

private fun getLoaderByClassId(id: Byte): TRLoader {
    return when (id.toInt()) {
        0 -> TRMethodCallTracePoint::load
        1 -> TRReadArrayTracePoint::load
        2 -> TRReadLocalVariableTracePoint::load
        3 -> TRReadTracePoint::load
        4 -> TRWriteArrayTracePoint::load
        5 -> TRWriteLocalVariableTracePoint::load
        6 -> TRWriteTracePoint::load
        else -> error("Unknown TRTracePoint class id $id")
    }
}
