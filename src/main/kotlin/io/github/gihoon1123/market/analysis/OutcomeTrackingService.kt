package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.pythonIso
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime

@Service
class OutcomeTrackingService(
    private val repository: OutcomeRepository,
    private val market: MarketDataClient,
    private val transaction: TransactionTemplate,
) {
    fun initialize(signalId: Long): Map<String, Any?> =
        transaction.execute {
            if (repository.signal(signalId) == null) throw ApiFailure(404, "신호를 찾을 수 없거나 추적 시작 실패")
            if (repository.outcome(signalId) ==
                null
            ) {
                repository.execute(
                    "INSERT INTO signal_outcomes(signal_id,is_complete,created_at,updated_at) VALUES (?,FALSE,?,?)",
                    signalId,
                    utcNow(),
                    utcNow(),
                )
            }
            val value = repository.outcome(signalId)!!
            mapOf(
                "message" to "결과 추적이 시작되었습니다",
                "signal_id" to signalId,
                "outcome_id" to value["id"],
                "tracking_started_at" to value["created_at"],
            )
        }!!

    fun summary(raw: Boolean = false): Map<String, Any?> {
        val all = repository.joined()
        val completed = all.count { it.truth("is_complete") }
        val pending = all.count { !it.truth("is_complete") && eligible(it, 0) }
        val rate = if (all.isEmpty()) 0.0 else Math.rint(completed * 10000.0 / all.size) / 100
        return if (!raw) {
            mapOf("total_tracked" to all.size, "completed" to completed, "pending" to pending, "completion_rate" to rate)
        } else {
            mapOf(
                "총_추적_개수" to all.size,
                "완료된_추적" to completed,
                "미완료_추적" to pending,
                "완료율" to rate,
                "시간대별_데이터_수집현황" to
                    OutcomeRepository.horizons.zip(listOf("1시간_후", "4시간_후", "1일_후", "1주일_후", "1개월_후")).associate { (h, label) ->
                        label to
                            all.count { it["price_${h}_after"] != null }
                    },
                "생성_시간" to utcNow().pythonIso(),
            )
        }
    }

    private fun eligible(
        value: Map<String, Any?>,
        hours: Int,
    ): Boolean {
        val elapsed = Duration.between(LocalDateTime.parse(value["triggered_at"].toString()), utcNow()).toMillis() / 3600000.0
        return elapsed >= hours && elapsed <= 1080 && OutcomeRepository.horizons.any { value["price_${it}_after"] == null }
    }

    fun update(
        hours: Int,
        enhanced: Boolean = false,
    ): Map<String, Any?> {
        val pending =
            repository
                .joined()
                .filter {
                    !it.truth(
                        "is_complete",
                    ) &&
                        eligible(it, hours)
                }.sortedBy { it["created_at"].toString() }
        var updated = 0
        var errors = 0
        var completed = 0
        for (value in pending) {
            try {
                val elapsed = Duration.between(LocalDateTime.parse(value["triggered_at"].toString()), utcNow()).toMillis() / 3600000.0
                var price = market.current(value["symbol"].toString())
                if (enhanced && price == null) {
                    repeat(2) { if (price == null) price = market.current(value["symbol"].toString()) }
                    if (price == null) price = value.number("current_price")
                }
                val fields = linkedMapOf<String, Any?>()
                if (price != null && price != 0.0) {
                    for ((h, age) in OutcomeRepository.horizons.zip(listOf(1, 4, 24, 168, 720))) {
                        if (elapsed >= age &&
                            value["price_${h}_after"] == null
                        ) {
                            fields["price_${h}_after"] = price
                        }
                    }
                    if (fields.isNotEmpty()) updated++
                }
                val base = value.number("current_price") ?: error("missing current_price")
                require(base != 0.0)
                val returns = mutableListOf<Double>()
                val bullish = bullish(value["signal_type"].toString())
                for (h in OutcomeRepository.horizons) {
                    val p = (fields["price_${h}_after"] as? Double) ?: value.number("price_${h}_after") ?: continue
                    val result = (p - base) / base * 100
                    fields["return_$h"] = result
                    returns.add(result)
                    // The repository intentionally has no 1h success update in the source.
                    if (h in listOf("1d", "1w", "1m")) fields["is_successful_$h"] = if (bullish) result > 0 else result < 0
                    if (h == "1m") fields["is_complete"] = true
                }
                if (returns.isNotEmpty()) {
                    fields["max_return"] = returns.max()
                    fields["min_return"] = returns.min()
                }
                fields["last_updated_at"] = utcNow()
                fields["updated_at"] = utcNow()
                transaction.executeWithoutResult { repository.update((value["id"] as Number).toLong(), fields) }
                if (elapsed >= 720) completed++
            } catch (_: Exception) {
                errors++
            }
        }
        return mapOf("updated" to updated, "errors" to errors, "completed" to completed, "total_processed" to pending.size)
    }

    fun details(signalId: Long): Map<String, Any?> {
        val signal = repository.signal(signalId) ?: throw ApiFailure(500, "신호를 찾을 수 없음: ID $signalId")
        val outcome =
            repository.outcome(signalId)
                ?: return mapOf(
                    "signal" to signalWire(signal),
                    "outcome" to null,
                    "message" to "아직 결과 추적이 시작되지 않았습니다.",
                    "tracking_status" to "미시작",
                )
        val age = Duration.between(LocalDateTime.parse(signal["triggered_at"].toString()), utcNow()).toMillis() / 3600000.0
        val status =
            when {
                outcome.truth("is_complete") -> "완료"
                age < 1 -> "대기 중 (1시간 미만)"
                age < 24 -> "진행 중 (1일 미만)"
                age < 168 -> "진행 중 (1주 미만)"
                age <
                    720 -> "진행 중 (1개월 미만)"
                else -> "완료 예정"
            }
        val analysis =
            linkedMapOf<String, Any?>(
                "tracking_completion" to
                    mapOf(
                        "completed_timeframes" to OutcomeRepository.horizons.filter { outcome["price_${it}_after"] != null },
                        "is_complete" to outcome.truth("is_complete"),
                    ),
                "signal_direction" to
                    mapOf(
                        "is_bullish" to bullish(signal["signal_type"].toString()),
                        "expected_direction" to if (bullish(signal["signal_type"].toString())) "상승" else "하락",
                        "signal_type" to signal["signal_type"],
                    ),
            )
        outcome.number("return_1d")?.let { r ->
            val grade =
                when {
                    r > 5 -> "A"
                    r > 2 -> "B"
                    r > 0 -> "C"
                    r > -2 -> "D"
                    else -> "F"
                }
            val description =
                when (grade) {
                    "A" -> "우수한 성과 (+%.1f%%)"
                    "B" -> "좋은 성과 (+%.1f%%)"
                    "C" -> "보통 성과 (+%.1f%%)"
                    "D" -> "약간 손실 (%.1f%%)"
                    else -> "큰 손실 (%.1f%%)"
                }.format(java.util.Locale.ROOT, r)
            analysis["performance_grade"] = mapOf("grade" to grade, "return_1d" to r, "description" to description)
        }
        return mapOf(
            "signal" to signalWire(signal),
            "outcome" to wire(outcome),
            "analysis" to analysis,
            "tracking_status" to status,
        )
    }

    fun test(signalId: Long): Map<String, Any?> {
        val signal = repository.signal(signalId) ?: throw ApiFailure(500, "신호를 찾을 수 없음: ID $signalId")
        repository.execute("DELETE FROM signal_outcomes WHERE signal_id=?", signalId)
        initialize(signalId)
        val base = signal.number("current_price") ?: error("missing price")
        val up =
            listOf(
                "breakout_up",
                "golden_cross",
                "oversold",
                "bullish",
            ).any { signal["signal_type"].toString().lowercase().contains(it) }
        val values = linkedMapOf<String, Any?>()
        for ((h, pct) in OutcomeRepository.horizons.zip(listOf(.5, 1.0, 2.0, 5.0, 10.0))) {
            val r = if (up) pct else -pct
            values["price_${h}_after"] = base * (1 + r / 100)
            values["return_$h"] = r
            if (h in listOf("1d", "1w", "1m")) values["is_successful_$h"] = if (bullish(signal["signal_type"].toString())) r > 0 else r < 0
        }
        values["is_complete"] = true
        values["max_return"] = if (up) 10.0 else -.5
        values["min_return"] = if (up) .5 else -10.0
        repository.update((repository.outcome(signalId)!!["id"] as Number).toLong(), values)
        val detail = details(signalId)
        return mapOf(
            "signal" to detail["signal"],
            "outcome" to detail["outcome"],
            "analysis" to detail["analysis"],
            "test_data" to
                (
                    mapOf("original_price" to base, "is_bullish" to up, "expected_direction" to if (up) "상승" else "하락") +
                        OutcomeRepository.horizons.associate { "price_$it" to values["price_${it}_after"] }
                ),
        )
    }

    companion object {
        fun signalWire(signal: Map<String, Any?>): Map<String, Any?> =
            listOf(
                "id",
                "symbol",
                "signal_type",
                "timeframe",
                "triggered_at",
                "current_price",
                "indicator_value",
                "signal_strength",
                "volume",
                "market_condition",
                "alert_sent",
                "created_at",
            ).associateWith { key ->
                if (key in
                    listOf("current_price", "indicator_value", "signal_strength")
                ) {
                    signal.number(key)?.takeIf { it != 0.0 }
                } else if (key ==
                    "alert_sent"
                ) {
                    signal[key]?.let { signal.truth(key) }
                } else {
                    signal[key]
                }
            }

        fun bullish(type: String): Boolean =
            listOf("breakout_up", "golden_cross", "oversold", "bullish", "touch_lower").any {
                it in
                    type.lowercase()
            }

        fun wire(o: Map<String, Any?>): Map<String, Any?> {
            fun nullable(key: String) = o.number(key)?.takeIf { it != 0.0 }
            return mapOf(
                "id" to o["id"],
                "signal_id" to o["signal_id"],
                "returns" to OutcomeRepository.horizons.associateWith { nullable("return_$it") },
                "extremes" to mapOf("max_return" to nullable("max_return"), "max_drawdown" to nullable("max_drawdown")),
                "success_rates" to
                    listOf("1h", "1d", "1w", "1m").associateWith {
                        o["is_successful_$it"]?.let { _ ->
                            o.truth("is_successful_$it")
                        }
                    },
                "analysis" to listOf("volatility_1d", "volatility_1w", "trend_strength").associateWith { nullable(it) },
                "metadata" to
                    mapOf(
                        "is_complete" to o.truth("is_complete"),
                        "last_updated_at" to o["last_updated_at"],
                        "created_at" to o["created_at"],
                    ),
            )
        }
    }
}
