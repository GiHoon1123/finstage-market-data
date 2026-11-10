package io.github.gihoon1123.market

import io.github.gihoon1123.market.operations.OperationsCaches
import io.github.gihoon1123.market.operations.RealtimeWebSocket
import io.github.gihoon1123.market.recovery.BackgroundExecutor
import io.github.gihoon1123.market.recovery.DailyPriceRepository
import io.github.gihoon1123.market.recovery.RecoveryService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/v2/technical-analysis")
class AsyncAnalysisController(
    private val service: AsyncAnalysisService,
    private val market: MarketDataClient,
    private val indicators: MarketController,
    private val caches: OperationsCaches,
    private val background: BackgroundExecutor,
    private val realtime: RealtimeWebSocket,
    private val prices: DailyPriceRepository,
    private val recovery: RecoveryService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/batch/indicators")
    fun batch(
        @RequestBody symbols: List<String>,
        @RequestParam(defaultValue = "1mo") period: String,
        @RequestParam(name = "batch_size", defaultValue = "5") @Min(1) @Max(20) size: Int,
    ): Map<String, Any?> {
        val batch = service.batchWithAvailability(symbols, period, size)
        if (batch.available.isEmpty()) throw ApiFailure(404, "유효한 가격 데이터를 가진 심볼이 없습니다")
        val results = batch.results
        return mapOf(
            "timestamp" to now(),
            "period" to period,
            "requested_symbols" to symbols.size,
            "analyzed_symbols" to results.size,
            "batch_size" to size,
            "results" to results,
        )
    }

    @GetMapping("/prices/monitor")
    fun monitor(
        @RequestParam(required = false) symbols: String?,
        @RequestParam(name = "alerts_only", defaultValue = "false") alertsOnly: Boolean,
    ): Map<String, Any?> {
        val results =
            service
                .monitor(symbols?.takeIf { it.isNotEmpty() }?.split(",")?.map(String::trim) ?: listOf("^IXIC", "^GSPC"))
                .filterValues { !alertsOnly || (it["alerts"] as? List<*>)?.isNotEmpty() == true }
        return mapOf(
            "timestamp" to now(),
            "monitored_symbols" to results.size,
            "total_alerts" to results.values.sumOf { (it["alerts"] as? List<*>)?.size ?: 0 },
            "symbols_with_alerts" to results.values.count { (it["alerts"] as? List<*>)?.isNotEmpty() == true },
            "results" to results,
        )
    }

    @GetMapping("/prices/batch")
    fun prices(
        @RequestParam symbols: String,
        @RequestParam(name = "include_history", defaultValue = "false") history: Boolean,
    ): Map<String, Any?> {
        val list = symbols.split(",").map(String::trim)
        val results = service.prices(list, history)
        return mapOf(
            "timestamp" to now(),
            "requested_symbols" to list.size,
            "successful_symbols" to results.values.count { it["current_price"] != null },
            "include_history" to history,
            "results" to results,
        )
    }

    @PostMapping("/analysis/comprehensive")
    fun comprehensive(
        @RequestBody symbols: List<String>,
        @RequestParam(defaultValue = "1mo") period: String,
        @RequestParam(name = "save_results", defaultValue = "false") save: Boolean,
    ): Map<String, Any?> {
        val results = service.comprehensive(symbols, period)
        if (save) {
            logger.info(
                "analysis_results_saved_background symbols_count={}; source persistence hook has no database write",
                symbols.size,
            )
        }
        return results
    }

    @PostMapping("/analysis/background")
    fun background(
        @RequestParam symbols: List<String>,
        @RequestParam(name = "analysis_types", defaultValue = "indicators,signals") types: List<String>,
    ): Map<String, Any?> {
        val id =
            background.submit {
                val priority = listOf("^IXIC", "^GSPC", "^DJI")
                val results =
                    (priority + symbols.filter { it !in priority }).associateWith { symbol ->
                        try {
                            val data = linkedMapOf<String, Any?>()
                            if ("indicators" in
                                types
                            ) {
                                data["indicators"] = mapOf("success" to true, "data" to indicators.indicators(symbol, "1mo", "all"))
                            }
                            if ("signals" in types) {
                                val rows = prices.prices(symbol, LocalDate.now().minusDays(30), LocalDate.now())
                                val signals =
                                    if (rows.size <
                                        200
                                    ) {
                                        mapOf("error" to "$symbol 데이터 부족 (최소 200일 필요)")
                                    } else {
                                        recovery.signals(symbol, 30)
                                    }
                                data["signals"] = mapOf("success" to true, "data" to signals)
                            }
                            data
                        } catch (_: Exception) {
                            mapOf("success" to false, "error" to "기술적 분석 실패")
                        }
                    }
                mapOf(
                    "task" to "technical_analysis_batch",
                    "status" to "completed",
                    "results" to results,
                    "processed_symbols" to symbols.size,
                    "analysis_types" to types,
                    "completed_at" to now(),
                )
            }
        return mapOf(
            "status" to "queued",
            "task_id" to id,
            "symbols" to symbols,
            "analysis_types" to types,
            "estimated_completion" to "5-10 minutes",
            "queued_at" to now(),
        )
    }

    @GetMapping("/analysis/status/{task_id}")
    fun status(
        @PathVariable("task_id") id: String,
    ): Map<String, Any?> {
        val result = background.result(id).orEmpty()
        return mapOf(
            "task_id" to id,
            "status" to (result["status"] ?: "unknown"),
            "progress" to (result["progress"] ?: 0),
            "result" to result["result"],
            "error" to result["error"],
            "created_at" to result["created_at"],
            "completed_at" to result["completed_at"],
        )
    }

    @GetMapping("/cache/stats")
    fun cache(): Map<String, Any?> =
        mapOf(
            "cache_name" to "technical_analysis",
            "statistics" to caches.cache("technical_analysis").stats(),
            "timestamp" to now(),
        )

    @PostMapping("/streaming/start")
    fun streaming(
        @RequestParam symbols: List<String>,
        @RequestParam(defaultValue = "rsi,macd,sma") indicators: List<String>,
        @RequestParam(name = "interval_seconds", defaultValue = "30") @Min(1) interval: Long,
    ): Map<String, Any?> {
        background.repeatEvery(interval) {
            symbols.forEach { symbol ->
                try {
                    val data = streamingData(symbol, indicators)
                    realtime.broadcast(mapOf("data" to data), "technical_analysis_update", "technical_analysis", symbol)
                } catch (_: Exception) {
                    logger.debug("Technical analysis streaming failed for {}", symbol)
                }
            }
        }
        return mapOf(
            "status" to "streaming_started",
            "symbols" to symbols,
            "indicators" to indicators,
            "interval_seconds" to interval,
            "started_at" to now(),
        )
    }

    internal fun streamingData(
        symbol: String,
        selected: List<String>,
    ): Map<String, Any?> {
        val rows = market.history(symbol, "1mo", "1d")?.validCandles().orEmpty()
        if (rows.isEmpty()) throw ApiFailure(404, "유효한 가격 데이터가 없습니다")
        val prices = rows.map { it.close }
        val result = linkedMapOf<String, Any?>()
        if ("rsi" in selected) {
            val rsi = TechnicalIndicators.rsi(prices)
            result["rsi"] =
                mapOf(
                    "current" to finite(rsi.last()),
                    "previous" to rsi.getOrNull(rsi.lastIndex - 1)?.let(::finite),
                    "signal" to
                        if (rsi.last() > 70) {
                            "overbought"
                        } else if (rsi.last() < 30) {
                            "oversold"
                        } else {
                            "neutral"
                        },
                )
        }
        if ("macd" in selected) {
            val data = TechnicalIndicators.macd(prices)
            result["macd"] =
                data.mapValues { finite(it.value.last()) } +
                ("trend" to if (data.getValue("histogram").last() > 0) "bullish" else "bearish")
        }
        if ("sma" in selected) {
            val ma20 = TechnicalIndicators.sma(prices, 20)
            val ma50 = TechnicalIndicators.sma(prices, 50)
            result["sma"] = mapOf("20" to finite(ma20.last()), "50" to finite(ma50.last()))
            cross(ma20, ma50)?.let { result["cross_signal"] = it }
        }
        return mapOf("symbol" to symbol, "timestamp" to now(), "indicators" to result)
    }

    @PostMapping("/alerts/subscribe")
    fun alerts(
        @RequestParam symbols: List<String>,
        @RequestParam(name = "alert_types", defaultValue = "rsi_overbought,rsi_oversold,golden_cross,death_cross") types: List<String>,
    ): Map<String, Any?> {
        val previous = ConcurrentHashMap<String, Double>()
        background.repeatEvery(60) {
            symbols.forEach { symbol ->
                try {
                    val prices =
                        market
                            .history(symbol, "1mo", "1d")
                            ?.close
                            .orEmpty()
                            .filterNotNull()
                    if (prices.isNotEmpty()) {
                        val alerts = mutableListOf<Map<String, Any?>>()
                        if (types.any { it.startsWith("rsi_") }) {
                            val rsi = TechnicalIndicators.rsi(prices).last()
                            val prior = previous.put(symbol, rsi)
                            val type =
                                if (rsi > 70 && (prior == null || prior <= 70)) {
                                    "rsi_overbought"
                                } else if (rsi < 30 && (prior == null || prior >= 30)) {
                                    "rsi_oversold"
                                } else {
                                    null
                                }
                            if (type != null && type in types) {
                                alerts.add(
                                    mapOf(
                                        "type" to type,
                                        "symbol" to symbol,
                                        "rsi_value" to rsi,
                                        "message" to
                                            "$symbol RSI ${if (type == "rsi_overbought") "과매수" else "과매도"} 구간 진입 (RSI: ${String.format(
                                                Locale.ROOT,
                                                "%.2f",
                                                rsi,
                                            )})",
                                    ),
                                )
                            }
                        }
                        val ma20 = TechnicalIndicators.sma(prices, 20)
                        val ma50 = TechnicalIndicators.sma(prices, 50)
                        val cross = cross(ma20, ma50)
                        if (cross != null && cross in types) {
                            alerts.add(
                                mapOf(
                                    "type" to cross,
                                    "symbol" to symbol,
                                    "sma_20" to ma20.last(),
                                    "sma_50" to ma50.last(),
                                    "message" to
                                        "$symbol ${if (cross == "golden_cross") "골든크로스" else "데드크로스"} 발생 (SMA20: ${String.format(
                                            Locale.ROOT,
                                            "%.2f",
                                            ma20.last(),
                                        )}, SMA50: ${String.format(Locale.ROOT, "%.2f", ma50.last())})",
                                ),
                            )
                        }
                        alerts.forEach { realtime.broadcast(mapOf("data" to it), "technical_alert", "alerts", symbol) }
                    }
                } catch (_: Exception) {
                    logger.debug("Technical alert cycle failed for {}", symbol)
                }
            }
        }
        return mapOf("status" to "subscribed", "symbols" to symbols, "alert_types" to types, "subscribed_at" to now())
    }

    private fun cross(
        fast: List<Double>,
        slow: List<Double>,
    ): String? {
        if (fast.size < 2 || slow.size < 2) return null
        val i = fast.lastIndex
        return if (fast[i - 1] <= slow[i - 1] && fast[i] > slow[i]) {
            "golden_cross"
        } else if (fast[i - 1] >= slow[i - 1] && fast[i] < slow[i]) {
            "death_cross"
        } else {
            null
        }
    }

    private fun finite(value: Double): Double? = value.takeIf { it.isFinite() }

    private fun now(): String = LocalDateTime.now().pythonIso()
}
