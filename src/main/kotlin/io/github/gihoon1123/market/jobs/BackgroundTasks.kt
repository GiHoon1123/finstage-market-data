package io.github.gihoon1123.market.jobs

import io.github.gihoon1123.market.TechnicalIndicators
import io.github.gihoon1123.market.pythonIso
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Random
import kotlin.math.exp
import kotlin.math.round

/** These five source jobs are demonstrations using synthetic data, not market feeds. */
class BackgroundTasks(
    private val unitDelayMillis: Long = 100,
) {
    val aliases =
        linkedMapOf(
            "process_dataset" to "app.common.services.background_tasks.process_large_dataset",
            "generate_report" to "app.common.services.background_tasks.generate_daily_report",
            "send_notifications" to "app.common.services.background_tasks.send_bulk_notifications",
            "cleanup_data" to "app.common.services.background_tasks.cleanup_old_data",
            "market_analysis" to "app.common.services.background_tasks.run_market_analysis",
        )

    fun register(queue: TaskQueue) {
        queue.register(
            aliases.getValue("process_dataset"),
        ) { args, kwargs -> dataset(string(args, kwargs, 0, "symbol"), string(args, kwargs, 1, "period", "1y")) }
        queue.register(
            aliases.getValue("generate_report"),
        ) { args, kwargs -> report(strings(args, kwargs, 0, "symbols"), string(args, kwargs, 1, "date", LocalDate.now().toString())) }
        queue.register(aliases.getValue("send_notifications")) { args, kwargs ->
            val value = kwargs["notifications"] ?: args.getOrNull(0)
            @Suppress("UNCHECKED_CAST")
            notifications(value as? List<Map<String, Any?>> ?: throw IllegalArgumentException("notifications is required"))
        }
        queue.register(aliases.getValue("cleanup_data")) { args, kwargs ->
            cleanup(
                ((kwargs["days_to_keep"] ?: args.getOrNull(0)) as? Number)?.toLong() ?: 30,
            )
        }
        queue.register(
            aliases.getValue("market_analysis"),
        ) { args, kwargs -> analysis(string(args, kwargs, 0, "analysis_type", "comprehensive")) }
    }

    private suspend fun dataset(
        symbol: String,
        period: String,
    ): Map<String, Any?> {
        val dates =
            LocalDate
                .of(2023, 1, 1)
                .datesUntil(LocalDate.of(2024, 1, 1))
                .filter {
                    it.dayOfWeek !in
                        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                }.toList()
        val random = Random(symbol.hashCode().toLong())
        var cumulative = 0.0
        val prices =
            dates.map {
                cumulative += 0.001 + 0.02 * random.nextGaussian()
                100 * exp(cumulative)
            }
        delay(unitDelayMillis * 20)
        return mapOf(
            "symbol" to symbol,
            "period" to period,
            "data_points" to dates.size,
            "memory_usage_mb" to null,
            "price_range" to mapOf("min" to prices.min(), "max" to prices.max(), "current" to prices.last()),
            "technical_indicators" to
                mapOf(
                    "sma_20" to TechnicalIndicators.sma(prices, 20).last(),
                    "sma_50" to TechnicalIndicators.sma(prices, 50).last(),
                    "rsi" to TechnicalIndicators.rsi(prices).last(),
                ),
            "processed_at" to LocalDateTime.now().pythonIso(),
        )
    }

    private suspend fun report(
        symbols: List<String>,
        date: String,
    ): Map<String, Any> {
        val start = System.nanoTime()
        val data = linkedMapOf<String, Any>()
        for (symbol in symbols) {
            val random = Random("${symbol}_$date".hashCode().toLong())
            data[symbol] =
                mapOf(
                    "symbol" to symbol,
                    "current_price" to rounded(50 + random.nextDouble() * 450),
                    "change_percent" to rounded(random.nextDouble() * 10 - 5),
                    "volume" to random.nextInt(49000001) + 1000000,
                    "market_cap" to 1000000000L + random.nextLong(999000000001L),
                    "pe_ratio" to rounded(10 + random.nextDouble() * 20),
                    "technical_score" to random.nextInt(100) + 1,
                )
            delay(unitDelayMillis)
        }
        return mapOf(
            "report_date" to date,
            "generated_at" to LocalDateTime.now().pythonIso(),
            "symbols" to symbols,
            "summary" to mapOf("total_symbols" to symbols.size, "processing_time_seconds" to rounded((System.nanoTime() - start) / 1e9)),
            "symbol_data" to data,
        )
    }

    private suspend fun notifications(values: List<Map<String, Any?>>): Map<String, Any> {
        val random = Random()
        val details =
            values.mapIndexed { index, notification ->
                delay(unitDelayMillis)
                if (random.nextDouble() <
                    0.9
                ) {
                    mapOf(
                        "index" to index,
                        "status" to "success",
                        "recipient" to (notification["recipient"] ?: "unknown"),
                        "message_type" to (notification["type"] ?: "unknown"),
                    )
                } else {
                    mapOf(
                        "index" to index,
                        "status" to "failed",
                        "recipient" to (notification["recipient"] ?: "unknown"),
                        "error" to "Network timeout",
                    )
                }
            }
        val successful = details.count { it["status"] == "success" }
        return mapOf(
            "total_notifications" to values.size,
            "successful_sends" to successful,
            "failed_sends" to values.size - successful,
            "send_details" to details,
            "sent_at" to LocalDateTime.now().pythonIso(),
        )
    }

    private suspend fun cleanup(days: Long): Map<String, Any> {
        val start = System.nanoTime()
        val random = Random()
        val tables =
            listOf("price_history", "technical_analysis", "alerts", "user_sessions", "api_logs").map { table ->
                delay(unitDelayMillis * 5)
                mapOf("table_name" to table, "records_deleted" to random.nextInt(9901) + 100, "space_freed_mb" to random.nextInt(100) + 1)
            }
        return mapOf(
            "cutoff_date" to LocalDateTime.now().minusDays(days).pythonIso(),
            "cleaned_tables" to tables,
            "total_records_deleted" to tables.sumOf { (it["records_deleted"] as Int) },
            "space_freed_mb" to tables.sumOf { (it["space_freed_mb"] as Int) },
            "cleanup_time_seconds" to rounded((System.nanoTime() - start) / 1e9),
        )
    }

    private suspend fun analysis(type: String): Map<String, Any> {
        val result = linkedMapOf<String, Any>("analysis_type" to type, "started_at" to LocalDateTime.now().pythonIso())
        val stages =
            linkedMapOf(
                "market_sentiment" to "시장 심리 분석",
                "sector_analysis" to "섹터별 분석",
                "predictions" to "예측 모델 실행",
                "risk_assessment" to "리스크 평가",
            )
        val random = Random()
        for ((key, title) in stages) {
            delay(unitDelayMillis * 20)
            result[key] =
                mapOf(
                    "stage" to title,
                    "confidence_score" to round((0.6 + random.nextDouble() * 0.35) * 1000) / 1000,
                    "key_findings" to listOf("$title 주요 발견사항 1", "$title 주요 발견사항 2"),
                    "processed_at" to LocalDateTime.now().pythonIso(),
                )
        }
        result["completed_at"] = LocalDateTime.now().pythonIso()
        result["total_processing_time"] = "약 8초"
        return result
    }

    private fun rounded(value: Double): Double = round(value * 100) / 100

    private fun string(
        args: List<Any?>,
        kwargs: Map<String, Any?>,
        index: Int,
        name: String,
        default: String? = null,
    ): String = (kwargs[name] ?: args.getOrNull(index) ?: default) as? String ?: throw IllegalArgumentException("$name is required")

    private fun strings(
        args: List<Any?>,
        kwargs: Map<String, Any?>,
        index: Int,
        name: String,
    ): List<String> =
        ((kwargs[name] ?: args.getOrNull(index)) as? List<*>)?.map {
            it as? String
                ?: throw IllegalArgumentException("$name must contain strings")
        }
            ?: throw IllegalArgumentException("$name is required")
}
