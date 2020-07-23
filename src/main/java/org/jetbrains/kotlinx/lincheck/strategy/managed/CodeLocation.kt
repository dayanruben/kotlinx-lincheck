/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

internal sealed class CodeLocation {
    protected abstract fun toStringImpl(): String
    override fun toString(): String = toStringImpl()
}

internal class ReadCodeLocation(private val fieldName: String?, private val stackTraceElement: StackTraceElement) : CodeLocation() {
    private var value: Any? = null

    override fun toStringImpl(): String = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("READ")
        if (value != null)
            append(": $value")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun addReadValue(value: Any?) {
        this.value = value
    }
}

internal class WriteCodeLocation(private val fieldName: String?, private val stackTraceElement: StackTraceElement) : CodeLocation() {
    private var value: Any? = null

    override fun toStringImpl(): String  = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("WRITE(")
        if (value != null)
            append("$value")
        append(") at ${stackTraceElement.shorten()}")
    }.toString()

    fun addWrittenValue(value: Any?) {
        this.value = value
    }
}

internal class MethodCallCodeLocation(private val methodName: String, private val stackTraceElement: StackTraceElement) : CodeLocation() {
    private var returnedValue: Any? = null
    private var parameters: Array<Any?>? = null

    override fun toStringImpl(): String = StringBuilder().apply {
        append("$methodName(")
        if (parameters != null)
            append(parameters!!.joinToString(","))
        append(")")
        if (returnedValue != null)
            append(": $returnedValue")
        append(" at ${stackTraceElement.shorten()}")
    }.toString()

    fun addReturnedValue(value: Any?) {
        this.returnedValue = value
    }

    fun addParameters(parameters: Array<Any?>) {
        this.parameters = parameters
    }
}

internal class MonitorEnterCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "MONITOR ENTER at " + stackTraceElement.shorten()
}

internal class MonitorExitCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "MONITOR EXIT at " + stackTraceElement.shorten()
}

internal class WaitCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "WAIT at " + stackTraceElement.shorten()
}

internal class NotifyCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "NOTIFY at " + stackTraceElement.shorten()
}

internal class ParkCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "PARK at " + stackTraceElement.shorten()
}

internal class UnparkCodeLocation(private val stackTraceElement: StackTraceElement) : CodeLocation() {
    override fun toStringImpl(): String = "UNPARK at " + stackTraceElement.shorten()
}

/**
 * Removes info about package in a stack trace element representation
 */
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString()
    for (i in stackTraceElement.indices.reversed())
        if (stackTraceElement[i] == '/')
            return stackTraceElement.substring(i + 1 until stackTraceElement.length)
    return stackTraceElement
}