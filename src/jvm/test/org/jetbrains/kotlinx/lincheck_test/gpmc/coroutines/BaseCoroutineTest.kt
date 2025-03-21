/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.kotlinx.lincheck.DEFAULT_INVOCATIONS_COUNT
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import java.io.Closeable
import java.util.concurrent.Executors

@OptIn(ExperimentalModelCheckingAPI::class)
abstract class BaseCoroutineTest(
    private val shouldFail: Boolean = false,
    private val invocations: Int = DEFAULT_INVOCATIONS_COUNT,
) {

    protected abstract fun createDispatcher(): CoroutineDispatcher

    protected fun executeCoroutineTest(block: (CoroutineDispatcher) -> Unit) {
        val result = runCatching {
            runConcurrentTest(invocations) {
                createDispatcher().let { dispatcher ->
                    try {
                        block(dispatcher)
                    } finally {
                        (dispatcher as? Closeable)?.close()
                    }
                }
            }
        }
        if (result.isFailure != shouldFail) {
            System.err.println(if (shouldFail) "Should've failed but succeeded" else "Should've succeeded but failed")
            throw result.exceptionOrNull()!!
        }
    }
}

abstract class FixedThreadPoolCoroutineTest(
    shouldFail: Boolean = false,
    invocations: Int = 100,
    private val nThreads: Int = 2,
) : BaseCoroutineTest(shouldFail, invocations) {

    override fun createDispatcher() = Executors.newFixedThreadPool(nThreads).asCoroutineDispatcher()
}