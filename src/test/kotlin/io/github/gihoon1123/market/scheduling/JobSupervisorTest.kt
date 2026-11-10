package io.github.gihoon1123.market.scheduling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JobSupervisorTest {
    @Test fun `duplicate executions are excluded without blocking independent jobs and failure is recoverable`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val independent = CountDownLatch(1)
        val attempts = AtomicInteger()
        JobSupervisor(
            mapOf(
                "slow" to {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "done"
                },
                "other" to {
                    independent.countDown()
                    42
                },
                "retry" to { if (attempts.incrementAndGet() == 1) error("temporary failure") else "recovered" },
            ),
        ).use { supervisor ->
            try {
                assertTrue(supervisor.trigger("slow"))
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertFalse(supervisor.trigger("slow"))
                assertEquals("skipped", (supervisor.runNow("slow") as Map<*, *>)["status"])
                assertTrue(supervisor.trigger("other"))
                assertTrue(independent.await(2, TimeUnit.SECONDS))
                assertEquals("failed", (supervisor.runNow("retry") as Map<*, *>)["status"])
                assertEquals("recovered", supervisor.runNow("retry"))
                val status = supervisor.status()
                assertEquals(2L, (status["slow"] as Map<*, *>)["skipped"])
                assertEquals(1L, (status["retry"] as Map<*, *>)["failures"])
                assertEquals(1L, (status["retry"] as Map<*, *>)["successes"])
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `interval scheduling coalesces overlap and lifecycle stops further submission`() {
        val completed = CountDownLatch(2)
        val active = AtomicInteger()
        val max = AtomicInteger()
        val supervisor =
            JobSupervisor(
                mapOf(
                    "periodic" to {
                        val current = active.incrementAndGet()
                        max.updateAndGet { maxOf(it, current) }
                        try {
                            Thread.sleep(60)
                            completed.countDown()
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                ),
            )
        try {
            supervisor.start(10)
            supervisor.start(10)
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, max.get())
            assertTrue(((supervisor.status()["periodic"] as Map<*, *>)["skipped"] as Long) > 0)
        } finally {
            supervisor.close()
        }
        assertFalse(supervisor.trigger("periodic"))
    }
}
