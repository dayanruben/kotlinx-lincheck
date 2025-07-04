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

import java.io.OutputStream
import java.io.PrintStream

private const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024

fun printRecorderTrace(output: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>, verbose: Boolean) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }
    PrintStream(output.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        rootCallsPerThread.forEachIndexed { i, root ->
            output.println("# Thread ${i+1}")
            printTRPoint(output, root, 0, verbose)
        }
    }
}

private fun printTRPoint(output: PrintStream, node: TRTracePoint, depth: Int, verbose: Boolean) {
    output.print(" ".repeat(depth * 2))
    output.println(node.toText(verbose))
    if (node is TRMethodCallTracePoint) {
        node.events.forEach {
            printTRPoint(output, it, depth + 1, verbose)
        }
    }
}
