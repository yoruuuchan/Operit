package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationDescriptionRegistryTest {

    @Test
    fun `reregistered descriptions still use the invocation parameters`() {
        val registry = OperationDescriptionRegistry()
        val tool = AITool("plugin:read", listOf(ToolParameter("path", "/notes")))
        assertNull(registry.getDescription(tool))
        registry.register(tool.name) { "old" }
        registry.register(tool.name) { invocation ->
            "Read ${invocation.parameters.single().value}"
        }
        assertEquals("Read /notes", registry.getDescription(tool))
    }

    @Test
    fun `concurrent plugin registration retains every operation description`() {
        val registry = OperationDescriptionRegistry()
        val workerCount = 4
        val descriptionsPerWorker = 1_000
        val workersReady = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val registrations = (0 until workerCount).map { worker ->
                executor.submit {
                    workersReady.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))

                    repeat(descriptionsPerWorker) { index ->
                        val toolName = "plugin-$worker:tool-$index"
                        registry.register(toolName) { "description:$toolName" }
                        assertEquals(
                            "description:$toolName",
                            registry.getDescription(AITool(name = toolName))
                        )
                    }
                }
            }

            assertTrue(workersReady.await(5, TimeUnit.SECONDS))
            start.countDown()
            registrations.forEach { registration ->
                registration.get(10, TimeUnit.SECONDS)
            }

            repeat(workerCount) { worker ->
                repeat(descriptionsPerWorker) { index ->
                    val toolName = "plugin-$worker:tool-$index"
                    assertEquals(
                        "description:$toolName",
                        registry.getDescription(AITool(name = toolName))
                    )
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
