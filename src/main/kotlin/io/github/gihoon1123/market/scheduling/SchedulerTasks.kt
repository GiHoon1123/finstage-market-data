package io.github.gihoon1123.market.scheduling

import io.github.gihoon1123.market.AsyncAnalysisService
import io.github.gihoon1123.market.communications.NewsService
import io.github.gihoon1123.market.jobs.TaskQueue
import io.github.gihoon1123.market.operations.OperationsCaches
import io.github.gihoon1123.market.pythonIso
import io.github.gihoon1123.market.recovery.RecoveryService
import io.github.gihoon1123.market.reporting.DailyReports
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

@Component
class SchedulerTasks(
    private val news: NewsService,
    private val prices: ScheduledPriceJobs,
    private val analysis: AsyncAnalysisService,
    private val recovery: RecoveryService,
    private val reports: ObjectProvider<DailyReports>,
    private val queue: TaskQueue,
    private val caches: OperationsCaches,
    @Value("\${scheduler.symbol-delay-ms:500}") private val delayMillis: Long = 500,
    @Value("\${scheduler.ml-symbols:}") private val configuredMlSymbols: String = "",
) {
    companion object {
        const val REPORT_TASK = "app.common.services.background_tasks.run_daily_comprehensive_report_background"
        const val HISTORY_TASK = "app.common.services.background_tasks.run_historical_data_collection_background"
        const val ANALYSIS_TASK = "app.common.services.background_tasks.run_technical_analysis_batch_background"
        val jobNames =
            listOf(
                "integrated_news",
                "yahoo_futures_news",
                "yahoo_stock_news",
                "investing_market_news",
                "yahoo_macro_news",
                "investing_macro_news",
                "high_price_update",
                "realtime_price_monitor",
                "previous_close_snapshot",
                "previous_high_snapshot",
                "previous_low_snapshot",
                "queued_daily_report",
                "queued_historical_collection",
                "queued_technical_analysis",
                "daily_comprehensive_report",
                "memory_optimization",
                "async_technical_analysis",
                "ml_training_data_collection",
            )
    }

    private val queued = ConcurrentHashMap<String, String>()

    @PostConstruct fun register() {
        queue.register(REPORT_TASK) { _, kwargs -> backgroundReports(symbols(kwargs, listOf("^IXIC", "^GSPC", "^DJI"))) }
        queue.register(HISTORY_TASK) { _, kwargs ->
            historical(
                symbols(kwargs, SchedulerSymbols.prices),
                kwargs["period"]?.toString() ?: "1y",
            )
        }
        queue.register(ANALYSIS_TASK) { _, kwargs ->
            technical(
                symbols(kwargs, listOf("^IXIC", "^GSPC", "^DJI", "AAPL", "MSFT")),
                (kwargs["analysis_types"] as? List<*>)?.map { it.toString() } ?: listOf("indicators", "signals"),
            )
        }
    }

    fun actions(): Map<String, () -> Any?> = jobNames.associateWith { name -> { execute(name) } }

    fun execute(name: String): Any? =
        when (name) {
            "integrated_news" ->
                mapOf(
                    "investing" to parallel(SchedulerSymbols.investingEconomic) { news.crawlInvesting(it) },
                    "yahoo" to parallel(SchedulerSymbols.indices) { news.crawl(it) },
                )
            "yahoo_futures_news" -> parallel(SchedulerSymbols.futures) { news.crawl(it) }
            "yahoo_stock_news" -> parallel(SchedulerSymbols.stocks) { news.crawl(it) }
            "investing_market_news" -> parallel(SchedulerSymbols.investingMarket) { news.crawlInvesting(it) }
            "yahoo_macro_news" -> parallel(SchedulerSymbols.yahooMacro) { news.crawl(it, true) }
            "investing_macro_news" -> parallel(SchedulerSymbols.investingMacro) { news.crawlInvesting(it, true) }
            "high_price_update" -> parallel(SchedulerSymbols.prices, delay = delayMillis * 4) { prices.high(it) }
            "previous_close_snapshot" -> parallel(SchedulerSymbols.prices) { prices.snapshot(it, "close") }
            "previous_high_snapshot" -> parallel(SchedulerSymbols.prices) { prices.snapshot(it, "high") }
            "previous_low_snapshot" -> parallel(SchedulerSymbols.prices) { prices.snapshot(it, "low") }
            "realtime_price_monitor" ->
                SchedulerSymbols.prices
                    .chunked(5)
                    .flatMap { batch ->
                        val results = parallel(batch, workers = 5, delay = 0)
                        if (delayMillis > 0) Thread.sleep(delayMillis * 2)
                        results.entries.map { it.key to it.value }
                    }.toMap()
            "queued_daily_report" -> enqueue(REPORT_TASK, mapOf("symbols" to listOf("^IXIC", "^GSPC", "^DJI")), 2, 1, 600000)
            "queued_historical_collection" ->
                enqueue(
                    HISTORY_TASK,
                    mapOf("symbols" to SchedulerSymbols.prices, "period" to "3mo"),
                    3,
                    1,
                    1800000,
                )
            "queued_technical_analysis" ->
                enqueue(
                    ANALYSIS_TASK,
                    mapOf(
                        "symbols" to listOf("^IXIC", "^GSPC", "^DJI", "AAPL", "MSFT"),
                        "analysis_types" to listOf("indicators", "signals"),
                    ),
                    2,
                    2,
                    900000,
                )
            "daily_comprehensive_report" ->
                requireNotNull(reports.ifAvailable) { "Daily report service is unavailable" }.generateDailyReport().also {
                    require(
                        it["status"] != "error",
                    ) { "Daily report generation failed: ${it["error"]}" }
                }
            "memory_optimization" -> memory()
            "async_technical_analysis" ->
                analysis.batch(
                    listOf("^IXIC", "^GSPC", "^DJI", "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN"),
                    "1mo",
                    3,
                )
            "ml_training_data_collection" -> mlCollection()
            else -> throw IllegalArgumentException("Unknown scheduled job: $name")
        }

    private fun parallel(
        symbols: List<String>,
        workers: Int = 2,
        delay: Long = delayMillis,
        action: (String) -> Any? = {
            prices.monitor(it)
        },
    ): Map<String, Any?> {
        if (symbols.isEmpty()) return emptyMap()
        return Executors.newFixedThreadPool(workers).use { executor ->
            val completion = ExecutorCompletionService<Pair<String, Any?>>(executor)
            symbols.forEach { symbol -> completion.submit(Callable { symbol to runCatching { action(symbol) }.getOrNull() }) }
            val results = linkedMapOf<String, Any?>()
            repeat(symbols.size) {
                val result = completion.take().get()
                results[result.first] = result.second
                if (delay > 0) Thread.sleep(delay)
            }
            results
        }
    }

    @Synchronized private fun enqueue(
        function: String,
        kwargs: Map<String, Any?>,
        priority: Int,
        retries: Int,
        timeout: Long,
    ): Map<String, Any?> {
        val existing = queued[function]?.let(queue::result)
        if (existing != null &&
            existing.status in setOf(TaskQueue.Status.PENDING, TaskQueue.Status.RUNNING, TaskQueue.Status.RETRYING)
        ) {
            return mapOf(
                "status" to "skipped",
                "task_id" to existing.id,
                "reason" to "previous_task_active",
            )
        }
        val id = queue.submit(function, kwargs = kwargs, priority = priority, maxRetries = retries, timeoutMillis = timeout)
        queued[function] = id
        return mapOf("status" to "submitted", "task_id" to id, "function" to function)
    }

    private fun backgroundReports(symbols: List<String>): Map<String, Any?> {
        val service = requireNotNull(reports.ifAvailable) { "Daily report service is unavailable" }
        val results =
            symbols.associateWith { symbol ->
                runCatching { mapOf("success" to true, "data" to service.comprehensive(symbol)) }.getOrElse {
                    mapOf(
                        "success" to false,
                        "error" to (it.message ?: "Report failed"),
                    )
                }
            }
        return mapOf(
            "task" to "daily_comprehensive_report",
            "status" to "completed",
            "results" to results,
            "processed_symbols" to symbols.size,
            "completed_at" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
        )
    }

    private fun historical(
        symbols: List<String>,
        period: String,
    ): Map<String, Any?> {
        val results = linkedMapOf<String, Any?>()
        for (batch in symbols.chunked(10)) {
            batch.forEach { symbol ->
                results[symbol] =
                    runCatching {
                        prices.collectHistory(
                            symbol,
                            period,
                            false,
                        )
                    }.getOrElse { mapOf("success" to false, "error" to it.message) }
            }
            if (delayMillis > 0) Thread.sleep(delayMillis * 2)
        }
        return mapOf(
            "task" to "historical_data_collection",
            "status" to "completed",
            "results" to results,
            "processed_symbols" to symbols.size,
            "period" to period,
            "completed_at" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
        )
    }

    private fun technical(
        symbols: List<String>,
        types: List<String>,
    ): Map<String, Any?> {
        val ordered = listOf("^IXIC", "^GSPC", "^DJI") + symbols.filter { it !in setOf("^IXIC", "^GSPC", "^DJI") }
        val indicators = if ("indicators" in types) analysis.batch(ordered, "6mo", 3) else emptyMap()
        val signals = if ("signals" in types) recovery.technical(ordered, listOf("signals"), 30) else emptyMap()
        val results =
            ordered.associateWith { symbol ->
                val values = linkedMapOf<String, Any?>()
                if ("indicators" in
                    types
                ) {
                    values["indicators"] = mapOf("success" to indicators.containsKey(symbol), "data" to indicators[symbol])
                }
                if ("signals" in types) {
                    val generated = signals[symbol]?.get("signals") as? Map<*, *>
                    values["signals"] = mapOf("success" to (generated != null && generated["status"] != "failed"), "data" to generated)
                }
                values
            }
        return mapOf(
            "task" to "technical_analysis_batch",
            "status" to "completed",
            "results" to results,
            "processed_symbols" to symbols.size,
            "analysis_types" to types,
            "completed_at" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
        )
    }

    fun mlCollection(
        symbols: List<String> =
            if (configuredMlSymbols.isBlank()) {
                SchedulerSymbols.mlTraining
            } else {
                configuredMlSymbols
                    .split(
                        ',',
                    ).map(String::trim)
                    .filter(String::isNotBlank)
            },
    ): Map<String, Any> {
        var success = 0
        var records = 0
        for (symbol in symbols) {
            val result = runCatching { prices.collectHistory(symbol, "1mo", true) }.getOrNull() ?: continue
            if (result["success"] == true) {
                success++
                records += (result["data_points"] as Number).toInt()
            }
        }
        return mapOf("successful_symbols" to success, "total_symbols" to symbols.size, "total_records" to records)
    }

    private fun memory(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val removed = caches.cleanup()
        System.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        val aggressive = after.toDouble() / runtime.maxMemory() * 100 > 85
        if (aggressive) caches.clear(null)
        return mapOf(
            "success" to true,
            "heap_before_bytes" to before,
            "heap_after_bytes" to after,
            "expired_items_removed" to removed.values.sum(),
            "aggressive" to aggressive,
        )
    }

    private fun symbols(
        kwargs: Map<String, Any?>,
        fallback: List<String>,
    ): List<String> = (kwargs["symbols"] as? List<*>)?.map { it.toString() } ?: fallback
}
