package io.github.gihoon1123.market.jobs

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class TaskQueueTest {
    @Test
    fun `scheduled retry survives stop and restart without a new ID`() =
        runBlocking {
            val queue = TaskQueue(1)
            val attempts = AtomicInteger()
            queue.register("transient") { _, _ ->
                if (attempts.incrementAndGet() == 1) error("first failure")
                "recovered"
            }
            val id = queue.submit("transient", retryDelayMillis = 5000)
            queue.start()
            withTimeout(3000) { while (queue.result(id)?.status != TaskQueue.Status.RETRYING) delay(10) }
            queue.stop()
            assertTrue(queue.start())
            try {
                withTimeout(3000) { while (queue.result(id)?.status != TaskQueue.Status.COMPLETED) delay(10) }
                assertEquals(2, attempts.get())
                assertEquals("recovered", queue.result(id)?.result)
                assertEquals(listOf(id), queue.recentResults().map { it.id })
            } finally {
                queue.stop()
            }
        }

    @Test
    fun `cancelled delayed retry does not execute again`() =
        runBlocking {
            val queue = TaskQueue(1)
            val attempts = AtomicInteger()
            queue.register("fail") { _, _ ->
                attempts.incrementAndGet()
                error("expected")
            }
            val id = queue.submit("fail", retryDelayMillis = 5000)
            queue.start()
            try {
                withTimeout(3000) { while (queue.result(id)?.status != TaskQueue.Status.RETRYING) delay(10) }
                assertTrue(queue.cancel(id))
                delay(100)
                assertEquals(TaskQueue.Status.CANCELLED, queue.result(id)?.status)
                assertEquals(1, attempts.get())
            } finally {
                queue.stop()
            }
        }

    @Test
    fun `one worker respects priority and FIFO within priority`() =
        runBlocking {
            val queue = TaskQueue(1)
            val order = Collections.synchronizedList(mutableListOf<String>())
            queue.register("record") { args, _ -> order.add(args[0] as String) }
            queue.submit("record", listOf("low"), priority = 3)
            queue.submit("record", listOf("critical1"), priority = 0)
            val last = queue.submit("record", listOf("critical2"), priority = 0)
            queue.start()
            try {
                withTimeout(3000) { while (order.size < 3) delay(10) }
                assertEquals(listOf("critical1", "critical2", "low"), order)
                assertEquals(TaskQueue.Status.COMPLETED, queue.result(last)?.status)
            } finally {
                queue.stop()
            }
        }

    @Test
    fun `failure retries are bounded and keep the original request ID`() =
        runBlocking {
            val queue = TaskQueue(1)
            val attempts = AtomicInteger()
            queue.register("fail") { _, _ ->
                attempts.incrementAndGet()
                error("expected failure")
            }
            val id = queue.submit("fail", maxRetries = 2, retryDelayMillis = 5)
            queue.start()
            try {
                withTimeout(3000) { while (queue.result(id)?.status != TaskQueue.Status.FAILED) delay(10) }
                assertEquals(3, attempts.get())
                assertEquals(2, queue.result(id)?.retryCount)
            } finally {
                queue.stop()
            }
        }

    @Test
    fun `timeouts fail and worker continues to the next task`() =
        runBlocking {
            val queue = TaskQueue(1)
            queue.register("slow") { _, _ ->
                delay(1000)
                "late"
            }
            queue.register("fast") { _, _ -> "ok" }
            val slow = queue.submit("slow", maxRetries = 0, timeoutMillis = 20)
            val fast = queue.submit("fast")
            queue.start()
            try {
                withTimeout(3000) { while (queue.result(fast)?.status != TaskQueue.Status.COMPLETED) delay(10) }
                assertEquals(TaskQueue.Status.FAILED, queue.result(slow)?.status)
                assertEquals("ok", queue.result(fast)?.result)
                assertFalse(queue.cancel(fast))
            } finally {
                queue.stop()
            }
        }
}
