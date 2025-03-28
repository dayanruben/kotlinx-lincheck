/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.trace_debugger.integration

import org.junit.Ignore
import org.junit.Test

class KotlinImmutableCollectionsTraceDebuggerIntegrationTest: AbstractTraceDebuggerIntegrationTest() {
    override val projectPath: String = "build/integrationTestProjects/kotlinx.collections.immutable"

    @Ignore
    @Test
    fun `tests contract list GuavaImmutableListTest_list`() {
        runGradleTest(
            testClassName = "tests.contract.list.GuavaImmutableListTest",
            testMethodName = "list",
            gradleCommands = listOf(
                ":kotlinx-collections-immutable:cleanJvmTest",
                ":kotlinx-collections-immutable:jvmTest",
            )
        )
    }
}
