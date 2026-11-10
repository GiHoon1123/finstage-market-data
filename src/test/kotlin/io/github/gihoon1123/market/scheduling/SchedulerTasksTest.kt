package io.github.gihoon1123.market.scheduling

import io.github.gihoon1123.market.AsyncAnalysisService
import io.github.gihoon1123.market.MarketCache
import io.github.gihoon1123.market.communications.NewsService
import io.github.gihoon1123.market.jobs.TaskQueue
import io.github.gihoon1123.market.operations.OperationsCaches
import io.github.gihoon1123.market.recovery.RecoveryService
import io.github.gihoon1123.market.reporting.DailyReports
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SchedulerTasksTest {
    @Test fun `all eighteen bindings preserve effective constants queue lifecycle and macro notification flags`() {
        val news = mockk<NewsService>()
        val prices = mockk<ScheduledPriceJobs>()
        val analysis = mockk<AsyncAnalysisService>()
        val recovery = mockk<RecoveryService>()
        every { news.crawl(any(), any()) } returns true
        every { news.crawlInvesting(any(), any()) } returns true
        every { prices.high(any()) } returns mapOf("status" to "saved")
        every { prices.snapshot(any(), any()) } returns mapOf("status" to "saved")
        every { prices.monitor(any()) } returns mapOf("status" to "checked")
        every { prices.collectHistory(any(), any(), any()) } returns mapOf("success" to true, "data_points" to 3)
        every { analysis.batch(any(), any(), any()) } returns emptyMap()
        every { recovery.technical(any(), any(), any()) } returns emptyMap()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val report =
            object : DailyReports {
                override fun generateDailyReport(): Map<String, Any?> = mapOf("status" to "success")

                override fun comprehensive(symbol: String): Map<String, Any?> {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return mapOf(
                        "symbol" to symbol,
                    )
                }
            }
        val factory = StaticListableBeanFactory(mapOf("reports" to report))
        val queue = TaskQueue(1)
        val tasks =
            SchedulerTasks(
                news,
                prices,
                analysis,
                recovery,
                factory.getBeanProvider(DailyReports::class.java),
                queue,
                OperationsCaches(MarketCache()),
                0,
                "",
            )
        tasks.register()
        queue.start()
        try {
            assertEquals(18, tasks.actions().size)
            assertEquals(listOf("^IXIC", "^GSPC"), SchedulerSymbols.prices)
            assertEquals(emptyList<String>(), SchedulerSymbols.mlTraining)
            val active = ActiveScheduler(tasks, false, 10)
            try {
                active.start()
                assertTrue(
                    active.supervisor
                        .status()
                        .values
                        .all { (it as Map<*, *>)["scheduled"] == false },
                )
            } finally {
                active.close()
            }
            for (name in listOf(
                "integrated_news",
                "yahoo_futures_news",
                "yahoo_stock_news",
                "investing_market_news",
                "yahoo_macro_news",
                "investing_macro_news",
                "high_price_update",
                "previous_close_snapshot",
                "previous_high_snapshot",
                "previous_low_snapshot",
                "realtime_price_monitor",
                "daily_comprehensive_report",
                "async_technical_analysis",
            )) {
                tasks.execute(name)
            }
            verify(exactly = 20) { news.crawl(any(), true) }
            verify(exactly = 8) { news.crawlInvesting(any(), true) }
            verify(exactly = 11) { news.crawl(any(), false) }
            verify(exactly = 5) { news.crawlInvesting(any(), false) }
            verify(exactly = 2) { prices.snapshot(any(), "close") }
            verify(exactly = 1) { analysis.batch(any(), "1mo", 3) }
            assertEquals(0, tasks.mlCollection()["total_symbols"])
            assertEquals(3, tasks.mlCollection(listOf("^TNX"))["total_records"])
            verify(exactly = 1) { prices.collectHistory("^TNX", "1mo", true) }
            val submitted = tasks.execute("queued_daily_report") as Map<*, *>
            assertEquals("submitted", submitted["status"])
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val duplicate = tasks.execute("queued_daily_report") as Map<*, *>
            assertEquals("skipped", duplicate["status"])
            assertEquals(submitted["task_id"], duplicate["task_id"])
            release.countDown()
            val id = submitted["task_id"].toString()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (queue.result(id)?.status != TaskQueue.Status.COMPLETED && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(TaskQueue.Status.COMPLETED, queue.result(id)?.status)
            val queuedIds =
                listOf("queued_historical_collection", "queued_technical_analysis").map { name ->
                    val value = tasks.execute(name) as Map<*, *>
                    assertEquals("submitted", value["status"])
                    value["task_id"].toString()
                }
            val queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (queuedIds.any { queue.result(it)?.status != TaskQueue.Status.COMPLETED } &&
                System.nanoTime() < queueDeadline
            ) {
                Thread.sleep(10)
            }
            queuedIds.forEach { assertEquals(TaskQueue.Status.COMPLETED, queue.result(it)?.status) }
            verify(exactly = 2) { prices.collectHistory(any(), "3mo", false) }
            verify(exactly = 1) { recovery.technical(any(), listOf("signals"), 30) }
        } finally {
            release.countDown()
            runBlocking { queue.stop() }
        }
        assertFalse(queue.isRunning)
    }
}
