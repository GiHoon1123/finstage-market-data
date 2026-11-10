package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.pythonIso
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Service
class PerformanceService(
    private val repository: OutcomeRepository,
) {
    fun all(
        horizon: String,
        minimum: Int,
    ): Map<String, Any?> {
        val h = horizon.takeIf { it in OutcomeRepository.horizons } ?: "1d"
        val successH = horizon.takeIf { it in listOf("1d", "1w", "1m") } ?: "1d"
        val rows = repository.joined()
        val grouped = rows.groupBy { it["signal_type"].toString() }.toSortedMap()
        val success =
            grouped.mapNotNull { (type, list) ->
                val evaluated = list.filter { it["is_successful_$successH"] != null }
                if (evaluated.size <
                    minimum
                ) {
                    null
                } else {
                    mapOf(
                        "signal_type" to type,
                        "total_count" to evaluated.size,
                        "success_count" to evaluated.count { it.truth("is_successful_$successH") },
                        "success_rate" to evaluated.count { it.truth("is_successful_$successH") }.toDouble() / evaluated.size,
                    )
                }
            }
        val average =
            grouped.mapNotNull { (type, list) ->
                val values = list.mapNotNull { it.number("return_$h") }
                if (values.size <
                    minimum
                ) {
                    null
                } else {
                    mapOf(
                        "signal_type" to type,
                        "total_count" to values.size,
                        "avg_return" to values.average(),
                        "max_return" to values.max(),
                        "min_return" to values.min(),
                    )
                }
            }
        val best =
            rows
                .filter { it["return_$h"] != null }
                .groupBy { Triple(it["signal_type"], it["symbol"], it["timeframe"]) }
                .filterValues {
                    it.size >=
                        minimum
                }.map { (key, list) ->
                    mapOf(
                        "signal_type" to key.first,
                        "symbol" to key.second,
                        "timeframe" to key.third,
                        "total_count" to list.size,
                        "avg_return" to list.mapNotNull { it.number("return_$h") }.average(),
                    )
                }.sortedByDescending { (it["avg_return"] as Number).toDouble() }
                .take(5)
        val rates = success.map { (it["success_rate"] as Number).toDouble() }
        val returns = average.map { (it["avg_return"] as Number).toDouble() }
        val excellent = success.filter { (it["success_rate"] as Number).toDouble() >= .7 }
        val poor =
            success.filter {
                (it["success_rate"] as Number).toDouble() <=
                    .4
            }
        val recommendations = mutableListOf<String>()
        excellent.firstOrNull()?.let { recommendations.add("✅ '${it["signal_type"]}' 신호는 우수한 성과를 보입니다. 적극 활용하세요.") }
        poor.firstOrNull()?.let { recommendations.add("⚠️ '${it["signal_type"]}' 신호는 성과가 좋지 않습니다. 사용을 재검토하세요.") }
        best.firstOrNull()?.let {
            recommendations.add(
                "🏆 '${it["signal_type"]}' (${it["symbol"]}, ${it["timeframe"]})가 가장 좋은 성과를 보입니다 (수익률: ${fmt(
                    (it["avg_return"] as Number).toDouble(),
                )}%).",
            )
        }
        if (rates.averageOrZero() >
            .6
        ) {
            recommendations.add("📈 전반적으로 신호 품질이 양호합니다.")
        } else if (rates.averageOrZero() <
            .4
        ) {
            recommendations.add("📉 신호 품질 개선이 필요합니다. 필터링 조건을 강화하세요.")
        }
        return mapOf(
            "summary" to
                mapOf(
                    "total_signal_types" to success.size,
                    "overall_success_rate" to rates.averageOrZero(),
                    "overall_avg_return" to returns.averageOrZero(),
                    "best_success_rate" to (rates.maxOrNull() ?: 0.0),
                    "worst_success_rate" to (rates.minOrNull() ?: 0.0),
                    "best_avg_return" to (returns.maxOrNull() ?: 0.0),
                    "worst_avg_return" to (returns.minOrNull() ?: 0.0),
                    "evaluation_period" to horizon,
                    "min_samples_required" to minimum,
                ),
            "by_signal_type" to mapOf("success_rates" to success, "average_returns" to average),
            "best_performers" to best,
            "excellent_signals" to excellent,
            "poor_signals" to poor,
            "recommendations" to recommendations,
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun specific(
        type: String,
        symbol: String?,
    ): Map<String, Any?> {
        val all = repository.joined().filter { it["signal_type"] == type }
        val selected = all.filter { symbol == null || it["symbol"] == symbol }
        val timeframe = timeframes(selected)
        val risk = risk(selected.mapNotNull { it.number("return_1d") })
        val recent = all.sortedByDescending { it["created_at"].toString() }.take(1000).filter { (it.number("return_1d") ?: 0.0) != 0.0 }
        val monthly =
            recent.groupBy { it["triggered_at"].toString().take(7) }.mapValues { (_, list) ->
                val values = list.mapNotNull { it.number("return_1d") }
                mapOf(
                    "count" to values.size,
                    "avg_return" to values.average(),
                    "win_rate" to values.count { it > 0 }.toDouble() / values.size,
                )
            }
        val market =
            mapOf(
                "strong_market" to mapOf("count" to recent.count { it.number("return_1d")!! > 2 }, "description" to "강한 시장 상황에서의 성과"),
                "weak_market" to mapOf("count" to recent.count { it.number("return_1d")!! < -2 }, "description" to "약한 시장 상황에서의 성과"),
                "neutral_market" to
                    mapOf("count" to recent.count { it.number("return_1d")!! in -2.0..2.0 }, "description" to "중립적 시장 상황에서의 성과"),
            )
        return mapOf(
            "signal_type" to type,
            "symbol" to symbol,
            "total_signals" to minOf(all.size, 1000),
            "timeframe_performance" to timeframe,
            "risk_metrics" to risk,
            "monthly_performance" to monthly,
            "market_condition_analysis" to market,
            "signal_quality_score" to qualityScore(timeframe, risk),
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun quality(
        type: String,
        symbol: String?,
    ): Map<String, Any?> {
        val rows = repository.joined().filter { it["signal_type"] == type && (symbol == null || it["symbol"] == symbol) }
        val tf = timeframes(rows)
        val risk = risk(rows.mapNotNull { it.number("return_1d") })
        val score = qualityScore(tf, risk)
        val (grade, recommendation) =
            when {
                score >= 80 -> "A" to "매우 우수한 신호. 적극 활용 권장"
                score >= 70 -> "B" to "좋은 신호. 활용 권장"
                score >= 60 ->
                    "C" to
                        "보통 신호. 다른 지표와 함께 사용"
                ; score >= 50 -> "D" to "약한 신호. 신중한 사용 필요"
                else -> "F" to "품질이 낮은 신호. 사용 비권장"
            }
        val strengths = mutableListOf<String>()
        val weaknesses = mutableListOf<String>()
        val rate =
            tf["1d"]?.number("success_rate") ?: 0.0
        val avg =
            tf["1d"]?.number("avg_return") ?: 0.0
        val drawdown = risk.number("max_drawdown") ?: 0.0
        if (rate > .7) {
            strengths.add("높은 승률 (${fmt(rate * 100)}%)")
        } else if (rate < .4) {
            weaknesses.add("낮은 승률 (${fmt(rate * 100)}%)")
        }
        if (avg > 2) {
            strengths.add("높은 평균 수익률 (${fmt(avg)}%)")
        } else if (avg < 0) {
            weaknesses.add("음수 평균 수익률 (${fmt(avg)}%)")
        }
        if (drawdown > -10) {
            strengths.add("낮은 최대 손실률")
        } else if (drawdown < -20) {
            weaknesses.add("높은 최대 손실률 (${fmt(drawdown)}%)")
        }
        return mapOf(
            "signal_type" to type,
            "symbol" to symbol,
            "quality_assessment" to mapOf("overall_score" to score, "grade" to grade, "recommendation" to recommendation),
            "detailed_analysis" to
                mapOf("strengths" to strengths, "weaknesses" to weaknesses, "sample_size" to (risk["total_trades"] ?: 0)),
            "performance_metrics" to mapOf("timeframe_performance" to tf, "risk_metrics" to risk),
            "analysis_timestamp" to utcNow().pythonIso(),
        )
    }

    fun simulate(
        types: List<String>,
        capital: Double,
        position: Double,
        stop: Double?,
        profit: Double?,
        days: Int,
    ): Map<String, Any?> {
        val end = utcNow()
        val start = end.minusDays(days.toLong())
        val rows =
            repository
                .joined()
                .filter {
                    it["signal_type"] in types &&
                        LocalDateTime.parse(it["triggered_at"].toString()) in start..end &&
                        it["return_1d"] != null
                }.sortedBy { it["triggered_at"].toString() }
        // Repeating a type in the source query repeats its trades as well.
        val expanded = rows.flatMap { row -> List(types.count { it == row["signal_type"] }) { row } }
        val result = simulateRows(expanded, capital, position, stop, profit)
        return result +
            mapOf(
                "strategy_config" to
                    mapOf(
                        "signal_types" to types,
                        "initial_capital" to capital,
                        "position_size" to position,
                        "stop_loss" to stop,
                        "take_profit" to profit,
                        "period" to mapOf("start" to start.pythonIso(), "end" to end.pythonIso()),
                    ),
                "analysis_timestamp" to utcNow().pythonIso(),
            )
    }

    companion object {
        private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

        fun timeframes(rows: List<Map<String, Any?>>): Map<String, Map<String, Any?>> =
            OutcomeRepository.horizons
                .mapNotNull { h ->
                    val evaluated = rows.filter { it["return_$h"] != null }
                    val values = evaluated.mapNotNull { it.number("return_$h") }
                    if (values.isEmpty()) {
                        null
                    } else {
                        val success = if (h in listOf("1h", "4h")) emptyList() else evaluated.filter { it["is_successful_$h"] != null }
                        h to
                            mapOf(
                                "avg_return" to values.average(),
                                "max_return" to values.max(),
                                "min_return" to values.min(),
                                "count" to values.size,
                                "success_rate" to
                                    if (success.isEmpty()) {
                                        null
                                    } else {
                                        success.count { it.truth("is_successful_$h") }.toDouble() /
                                            success.size
                                    },
                            )
                    }
                }.toMap()

        fun risk(values: List<Double>): Map<String, Any?> {
            if (values.isEmpty()) return emptyMap()
            val mean = values.average()
            val vol = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            val gains =
                values
                    .filter {
                        it >
                            0
                    }.sum()
            ; val losses = abs(values.filter { it < 0 }.sum())
            return mapOf(
                "total_trades" to values.size,
                "avg_return" to mean,
                "max_return" to values.max(),
                "min_return" to values.min(),
                "max_drawdown" to values.min(),
                "volatility" to vol,
                "sharpe_ratio" to
                    if (vol >
                        0
                    ) {
                        mean / vol
                    } else {
                        0.0
                    },
                "win_rate" to values.count { it > 0 }.toDouble() / values.size,
                "profit_factor" to if (losses > 0) gains / losses else Double.POSITIVE_INFINITY,
            )
        }

        fun qualityScore(
            tf: Map<String, Map<String, Any?>>,
            risk: Map<String, Any?>,
        ): Double {
            val one = tf["1d"] ?: emptyMap()
            return min(
                100.0,
                min((one.number("success_rate") ?: 0.0) * 100, 20.0) + (2 * (one.number("avg_return") ?: 0.0)).coerceIn(0.0, 20.0) +
                    max(15 + (risk.number("max_drawdown") ?: 0.0) * .75, 0.0) +
                    min((risk.number("profit_factor") ?: 0.0) * 7.5, 15.0) +
                    min((risk.number("total_trades") ?: 0.0) * .5, 10.0),
            )
        }

        fun simulateRows(
            rows: List<Map<String, Any?>>,
            initial: Double,
            position: Double,
            stop: Double?,
            profit: Double?,
        ): Map<String, Any?> {
            var capital = initial
            var peak = initial
            var drawdown = 0.0
            val trades =
                rows.map { row ->
                    val amount = capital * position
                    val raw = row.number("return_1d")!! / 100
                    val actual =
                        when {
                            stop != null && stop != 0.0 && raw < stop -> stop
                            profit != null && profit != 0.0 && raw > profit -> profit
                            else -> raw
                        }
                    val pnl = amount * actual
                    capital += pnl
                    peak = max(peak, capital)
                    drawdown = max(drawdown, (peak - capital) / peak)
                    mapOf(
                        "date" to row["triggered_at"],
                        "signal_type" to row["signal_type"],
                        "symbol" to row["symbol"],
                        "entry_price" to row.number("current_price"),
                        "signal_return" to raw,
                        "actual_return" to actual,
                        "trade_amount" to amount,
                        "profit_loss" to pnl,
                        "capital_after" to capital,
                    )
                }
            val wins = trades.map { (it["profit_loss"] as Number).toDouble() }.filter { it > 0 }
            val losses =
                trades.map { (it["profit_loss"] as Number).toDouble() }.filter {
                    it <
                        0
                }
            val avgWin = wins.averageOrZero()
            val avgLoss = losses.averageOrZero()
            return mapOf(
                "performance" to
                    mapOf(
                        "total_trades" to trades.size,
                        "initial_capital" to initial,
                        "final_capital" to capital,
                        "total_return_pct" to (capital - initial) / initial * 100,
                        "max_drawdown_pct" to drawdown * 100,
                        "win_rate" to if (trades.isEmpty()) 0.0 else wins.size.toDouble() / trades.size,
                        "avg_win" to avgWin,
                        "avg_loss" to avgLoss,
                        "profit_factor" to
                            if (avgLoss !=
                                0.0
                            ) {
                                abs(avgWin / avgLoss)
                            } else {
                                Double.POSITIVE_INFINITY
                            },
                        "winning_trades" to wins.size,
                        "losing_trades" to losses.size,
                    ),
                "trades" to trades.takeLast(20),
            )
        }
    }
}

/** JSON has no infinity; keep calculations intact and expose undefined ratios as null. */
internal fun analysisJson(value: Any?): Any? =
    when (value) {
        is Double -> value.takeIf { it.isFinite() }
        is Float -> value.takeIf { it.isFinite() }
        is Map<*, *> -> value.mapValues { (_, item) -> analysisJson(item) }
        is List<*> -> value.map { analysisJson(it) }
        else -> value
    }
