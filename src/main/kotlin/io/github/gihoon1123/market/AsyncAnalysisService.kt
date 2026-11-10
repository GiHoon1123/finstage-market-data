package io.github.gihoon1123.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

@Service
class AsyncAnalysisService(
    private val market: MarketDataClient,
    private val summaries: PriceSummaryRepository,
) {
    data class Batch(
        val available: Set<String>,
        val results: Map<String, Map<String, Any?>>,
    )

    fun batchWithAvailability(
        symbols: List<String>,
        period: String,
        concurrency: Int,
    ): Batch {
        val fetched = parallel(symbols, concurrency) { symbol -> market.history(symbol, period, "1d")?.validCandles().orEmpty() }
        val available = fetched.filterValues { it.isNotEmpty() }
        val results = available.filterValues { it.size >= 2 }.mapValues { (symbol, candles) -> analyze(symbol, candles) }
        return Batch(available.keys, results)
    }

    fun batch(
        symbols: List<String>,
        period: String,
        concurrency: Int,
    ): Map<String, Map<String, Any?>> = batchWithAvailability(symbols, period, concurrency).results

    fun analyze(
        symbol: String,
        candles: List<TechnicalIndicators.Candle>,
    ): Map<String, Any?> {
        require(candles.size >= 2)
        val prices = candles.map { it.close }

        fun series(values: List<Double>): Map<String, Double?> =
            values.mapIndexed { index, value -> index.toString() to finite(value) }.toMap()
        val rsi = TechnicalIndicators.rsi(prices)
        val macd = TechnicalIndicators.macd(prices)
        val macdValue = macd.getValue("macd").last()
        val macdSignal = macd.getValue("signal").last()
        val histogram = macd.getValue("histogram").last()
        val signals = linkedMapOf<String, String>()
        val currentRsi = rsi.last()
        val previousRsi = rsi[rsi.lastIndex - 1]
        if (previousRsi < 70 && currentRsi >= 70) {
            signals["rsi"] = "overbought"
        } else if (previousRsi > 30 && currentRsi <= 30) {
            signals["rsi"] = "oversold"
        }
        if (macdValue > macdSignal && histogram > 0) {
            signals["macd"] = "bullish"
        } else if (macdValue < macdSignal && histogram < 0) {
            signals["macd"] = "bearish"
        }
        val indicators =
            mapOf(
                "sma" to listOf(5, 10, 20, 50, 200).associate { it.toString() to series(TechnicalIndicators.sma(prices, it)) },
                "ema" to listOf(12, 26).associate { it.toString() to series(TechnicalIndicators.ema(prices, it)) },
                "rsi" to mapOf("current" to finite(currentRsi), "previous" to finite(previousRsi)),
                "macd" to
                    mapOf(
                        "current_macd" to finite(macdValue),
                        "current_signal" to finite(macdSignal),
                        "current_histogram" to finite(histogram),
                    ),
                "bollinger" to TechnicalIndicators.bollinger(prices).mapValues { finite(it.value.last()) },
                "stochastic" to TechnicalIndicators.stochastic(candles).mapValues { finite(it.value.last()) },
            )
        return mapOf(
            "timestamp" to now(),
            "symbol" to symbol,
            "current_price" to prices.last(),
            "price_change_pct" to finite((prices.last() - prices[prices.lastIndex - 1]) / prices[prices.lastIndex - 1] * 100),
            "signals" to signals,
            "indicators" to indicators,
        )
    }

    fun prices(
        symbols: List<String>,
        history: Boolean,
    ): Map<String, Map<String, Any?>> =
        parallel(symbols, 10) { symbol ->
            val current = market.current(symbol)
            if (history) {
                mapOf("current_price" to current, "history" to market.history(symbol, "5d", "1d"))
            } else {
                mapOf(
                    "current_price" to current,
                )
            }
        }

    fun monitor(symbols: List<String>): Map<String, Map<String, Any?>> =
        parallel(symbols, 3) { symbol ->
            val price = market.current(symbol)
            if (price == null) {
                null
            } else {
                try {
                    val snapshot = summaries.latestSnapshot(symbol)
                    val high = summaries.latestHigh(symbol)
                    val analysis = linkedMapOf<String, Any?>()
                    val alerts = mutableListOf<Map<String, String>>()
                    snapshot?.close?.takeIf { it != 0.0 }?.let { base ->
                        analysis["prev_close"] =
                            mapOf("base_price" to base, "change_amount" to price - base, "change_percent" to (price - base) / base * 100)
                    }
                    high?.price?.takeIf { it != 0.0 }?.let { base ->
                        val percent = (price - base) / base * 100
                        analysis["all_time_high"] =
                            mapOf(
                                "base_price" to base,
                                "change_amount" to price - base,
                                "change_percent" to percent,
                                "record_date" to high.at.pythonIso(),
                            )
                        if (price >
                            base
                        ) {
                            alerts.add(
                                mapOf(
                                    "type" to "new_high",
                                    "message" to "$symbol 신고가 달성: ${String.format(Locale.ROOT, "%.2f", price)}",
                                    "severity" to "info",
                                ),
                            )
                        } else if (percent <= -10) {
                            alerts.add(
                                mapOf(
                                    "type" to "drop_from_high",
                                    "message" to "$symbol 최고가 대비 ${String.format(Locale.ROOT, "%.1f", -percent)}% 하락",
                                    "severity" to "warning",
                                ),
                            )
                        }
                    }
                    mapOf(
                        "symbol" to symbol,
                        "current_price" to price,
                        "timestamp" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
                        "alerts" to alerts,
                        "analysis" to analysis,
                    )
                } catch (_: Exception) {
                    mapOf(
                        "symbol" to symbol,
                        "error" to "기준 데이터 조회 실패",
                        "timestamp" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
                    )
                }
            }
        }.filterValues { it != null }.mapValues { it.value!! }

    fun comprehensive(
        symbols: List<String>,
        period: String,
    ): Map<String, Any?> {
        val monitoring = monitor(symbols)
        val batch = batchWithAvailability(symbols, period, 3)
        val technical = batch.results
        val results =
            symbols.associateWith { symbol ->
                mapOf(
                    "price_monitoring" to (monitoring[symbol] ?: emptyMap<String, Any?>()),
                    "technical_analysis" to (technical[symbol] ?: emptyMap<String, Any?>()),
                    "data_available" to (symbol in batch.available),
                )
            }
        return mapOf(
            "timestamp" to now(),
            "analysis_period" to period,
            "analyzed_symbols" to results.size,
            "symbols_with_data" to batch.available.size,
            "total_alerts" to monitoring.values.sumOf { (it["alerts"] as? List<*>)?.size ?: 0 },
            "results" to results,
        )
    }

    private fun <T> parallel(
        symbols: List<String>,
        concurrency: Int,
        work: (String) -> T,
    ): Map<String, T> =
        runBlocking {
            val semaphore = Semaphore(concurrency)
            symbols.map { symbol -> async(Dispatchers.IO) { semaphore.withPermit { symbol to work(symbol) } } }.awaitAll().toMap()
        }

    private fun finite(value: Double): Double? = value.takeIf { it.isFinite() }

    private fun now(): String = LocalDateTime.now().pythonIso()
}
