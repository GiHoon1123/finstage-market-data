package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.SignalRepository
import io.github.gihoon1123.market.pythonIso
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

@Service
class PatternService(
    private val patterns: PatternRepository,
    private val signals: SignalRepository,
    private val market: MarketDataClient,
) {
    fun discover(
        symbol: String,
        timeframe: String,
    ): Map<String, Any?> {
        val recent = signals.find(symbol = symbol, timeframe = timeframe, start = utcNow().minusDays(30)).reversed()
        val candidates = sequences(recent)
        val saved = mutableListOf<SignalPattern>()
        for (sequence in candidates) {
            val name = sequence.take(3).joinToString("_then_") { it["signal_type"].toString() }
            if (patterns.find(symbol, name).isNotEmpty()) continue
            saved.add(patterns.create(name, symbol, timeframe, sequence.map { (it["id"] as Number).toLong() }, marketCondition(symbol)))
        }
        return mapOf(
            "symbol" to symbol,
            "timeframe" to timeframe,
            "discovered_patterns" to candidates.size,
            "saved_patterns" to saved.size,
            "patterns" to
                saved.map {
                    mapOf(
                        "name" to it.name,
                        "type" to it.type,
                        "start" to it.start.pythonIso(),
                        "end" to it.end.pythonIso(),
                        "duration_hours" to (it.duration ?: 0.0),
                    )
                },
        )
    }

    private fun marketCondition(symbol: String): String {
        val prices = market.history(symbol, "1mo", "1d")?.close?.filterNotNull() ?: return "unknown"
        if (prices.size < 20) return "unknown"
        val change = (prices.last() - prices[prices.size - 5]) / prices[prices.size - 5]
        return when {
            change > .05 -> "bullish"
            change < -.05 -> "bearish"
            else -> "sideways"
        }
    }

    fun analysis(
        name: String?,
        symbol: String?,
        minimum: Int,
    ): Map<String, Any?> {
        val filters = mapOf("pattern_name" to name, "symbol" to symbol, "min_occurrences" to minimum)
        val groups = patterns.find(symbol, name).groupBy { it.name to it.symbol }.filterValues { it.size >= minimum }
        if (groups.isEmpty()) return mapOf("message" to "분석할 패턴이 없습니다.", "filters" to filters)
        val detail =
            groups.map { (key, list) ->
                // Source limits instances to 100 and only applies the caller's symbol filter.
                val instances = patterns.find(symbol, key.first).take(100)
                mapOf(
                    "pattern_name" to key.first,
                    "symbol" to key.second,
                    "total_occurrences" to list.size,
                    "avg_duration_hours" to list.mapNotNull { it.duration }.averageOrZero(),
                    "performance_metrics" to metrics(instances),
                    "market_conditions" to
                        instances.groupingBy { it.market ?: "unknown" }.eachCount().mapValues { (_, count) ->
                            mapOf(
                                "count" to count,
                                "percentage" to count * 100.0 / instances.size,
                            )
                        },
                )
            }
        val best = detail.maxBy { ((it["performance_metrics"] as Map<*, *>)["success_rate"] as Number).toDouble() }
        return mapOf(
            "summary" to
                mapOf(
                    "total_patterns" to detail.size,
                    "total_occurrences" to groups.values.sumOf { it.size },
                    "avg_success_rate" to
                        detail.map { ((it["performance_metrics"] as Map<*, *>)["success_rate"] as Number).toDouble() }.average(),
                    "best_pattern" to
                        mapOf(
                            "name" to best["pattern_name"],
                            "success_rate" to (best["performance_metrics"] as Map<*, *>)["success_rate"],
                            "occurrences" to best["total_occurrences"],
                        ),
                ),
            "pattern_analysis" to detail,
            "filters" to filters,
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun successful(
        symbol: String?,
        threshold: Double,
        minimum: Int,
    ): Map<String, Any?> {
        val groups = patterns.find(symbol).groupBy { it.name to it.symbol }.filterValues { it.size >= minimum }
        val found =
            groups
                .mapNotNull { (key, list) ->
                    val instances = patterns.find(symbol, key.first).take(100)
                    val count = instances.count { (it.duration ?: 0.0) >= 1.0 }
                    val rate = count.toDouble() / instances.size
                    if (rate <
                        threshold
                    ) {
                        null
                    } else {
                        mapOf(
                            "pattern_name" to key.first,
                            "symbol" to key.second,
                            "success_rate" to rate,
                            "total_occurrences" to instances.size,
                            "successful_occurrences" to count,
                            "avg_duration_hours" to list.mapNotNull { it.duration }.averageOrZero(),
                            "last_occurrence" to instances.maxOf { it.end }.pythonIso(),
                        )
                    }
                }.sortedByDescending { (it["success_rate"] as Number).toDouble() }
        return mapOf(
            "successful_patterns" to found,
            "criteria" to mapOf("success_threshold" to threshold, "min_occurrences" to minimum, "symbol" to symbol),
            "summary" to
                mapOf(
                    "total_patterns_analyzed" to groups.size,
                    "successful_patterns_found" to found.size,
                    "best_success_rate" to (found.firstOrNull()?.get("success_rate") ?: 0.0),
                ),
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun test(symbol: String): Map<String, Any?> {
        val descriptions = listOf("RSI 과매도 후 이동평균선 돌파", "볼린저 밴드 수축 후 돌파", "골든크로스와 거래량 급증")
        val expected = listOf(.75, .65, .8)
        val created =
            testNames.mapIndexedNotNull { index, name ->
                runCatching {
                    patterns.create(
                        name,
                        symbol,
                        "15min",
                        listOf(1L, 2L, 3L),
                        if (index % 2 ==
                            0
                        ) {
                            "bullish"
                        } else {
                            "bearish"
                        },
                    )
                }.getOrNull()?.let {
                    mapOf(
                        "id" to it.id,
                        "name" to name,
                        "description" to descriptions[index],
                        "expected_success_rate" to expected[index],
                    )
                }
            }
        return mapOf(
            "test_summary" to mapOf("symbol" to symbol, "created_patterns" to created.size, "test_patterns" to created),
            "pattern_analysis" to analysis(null, symbol, 1),
            "successful_patterns" to successful(symbol, .5, 1),
            "test_timestamp" to utcNow().pythonIso(),
        )
    }

    fun cleanup(symbol: String): Map<String, Any?> =
        mapOf(
            "deleted_count" to patterns.delete(testNames.flatMap { patterns.find(symbol, it).take(100) }.map { it.id }),
            "deleted_patterns" to testNames,
            "symbol" to symbol,
        )

    companion object {
        val testNames = listOf("RSI_oversold_then_MA_breakout", "BB_squeeze_then_breakout", "Golden_cross_with_volume")

        fun sequences(signals: List<Map<String, Any?>>): List<List<Map<String, Any?>>> {
            val result = mutableListOf<List<Map<String, Any?>>>()
            for (i in 0 until (signals.size - 1).coerceAtLeast(0)) {
                for (j in i + 2 until minOf(i + 6, signals.size + 1)) {
                    val sequence = signals.subList(i, j)
                    if (sequence.zipWithNext().all { (a, b) ->
                            Duration
                                .between(
                                    LocalDateTime.parse(a["triggered_at"].toString()),
                                    LocalDateTime.parse(b["triggered_at"].toString()),
                                ).toMillis() <=
                                86400000
                        }
                    ) {
                        result.add(sequence)
                    }
                }
            }
            return result
        }

        fun metrics(values: List<SignalPattern>): Map<String, Any?> {
            if (values.isEmpty()) return emptyMap()
            val durations = values.mapNotNull { it.duration?.takeIf { d -> d != 0.0 } }
            val count = values.count { (it.duration ?: 0.0) >= 1.0 }
            return mapOf(
                "total_count" to values.size,
                "avg_duration_hours" to durations.averageOrZero(),
                "min_duration_hours" to (durations.minOrNull() ?: 0.0),
                "max_duration_hours" to (durations.maxOrNull() ?: 0.0),
                "success_count" to count,
                "success_rate" to count.toDouble() / values.size,
            )
        }
    }
}

internal fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
