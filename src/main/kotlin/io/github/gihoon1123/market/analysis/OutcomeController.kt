package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.pythonIso
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sqrt

@RestController
@RequestMapping("/api/technical-analysis/outcomes")
class OutcomeController(
    private val repository: OutcomeRepository,
    private val tracking: OutcomeTrackingService,
) {
    @GetMapping("/tracking/summary")
    fun summary() = tracking.summary()

    @GetMapping("/tracking/pending")
    fun pending(
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) limit: Int,
    ): List<Map<String, Any?>> =
        repository.joined().filter { !it.truth("is_complete") }.sortedByDescending { it["created_at"].toString() }.take(limit).map { row ->
            mapOf(
                "outcome_id" to row["id"],
                "signal_id" to row["signal_id"],
                "signal_type" to row["signal_type"],
                "symbol" to row["symbol"],
                "signal_time" to row["signal_created_at"],
                "elapsed_hours" to
                    Duration.between(LocalDateTime.parse(row["signal_created_at"].toString()), LocalDateTime.now()).toMillis() / 3600000.0,
            ) +
                listOf("1h", "1d", "1w", "1m").associate { "price_${it}_completed" to (row["price_${it}_after"] != null) }
        }

    @GetMapping("/performance/by-signal-type")
    fun byType(
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int,
    ): List<Map<String, Any?>> =
        repository
            .joined()
            .filter { it.truth("is_complete") }
            .groupBy { it["signal_type"] }
            .map { (type, rows) ->
                mapOf("signal_type" to type, "total_signals" to rows.size) +
                    listOf("1h", "1d", "1w")
                        .flatMap { h ->
                            val values = rows.mapNotNull { it.number("return_$h") }
                            listOf(
                                "avg_return_$h" to values.averageOrZero().takeIf { it != 0.0 },
                                "success_rate_$h" to values.count { it > 0 } * 100.0 / rows.size,
                            )
                        }.toMap()
            }.sortedWith(
                compareByDescending<Map<String, Any?>> {
                    (it["avg_return_1d"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY
                },
            ).take(limit)

    @GetMapping("/performance/top-performers")
    fun top(
        @RequestParam(defaultValue = "1d") @Pattern(regexp = "1h|1d|1w") timeframe: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int,
    ) = repository
        .joined()
        .filter {
            it.truth("is_complete") &&
                it["return_$timeframe"] != null
        }.sortedByDescending { it.number("return_$timeframe") }
        .take(limit)
        .map {
            mapOf(
                "signal_type" to it["signal_type"],
                "symbol" to it["symbol"],
                "return_value" to it.number("return_$timeframe"),
                "signal_time" to it["signal_created_at"],
                "signal_price" to it.number("current_price")?.takeIf { p -> p != 0.0 },
            )
        }

    @PostMapping("/backtesting/simple")
    fun backtest(
        @RequestParam(name = "signal_types") types: List<String>,
        @RequestParam(name = "start_date", required = false) start: String?,
        @RequestParam(name = "end_date", required = false) end: String?,
    ): Map<String, Any?> {
        val from = start?.let { parseDate(it) } ?: LocalDateTime.now().minusDays(365)
        val to = end?.let { parseDate(it) } ?: LocalDateTime.now()
        val returns =
            repository
                .joined()
                .filter {
                    it["signal_type"] in types &&
                        it.truth("is_complete") &&
                        LocalDateTime.parse(it["signal_created_at"].toString()) in from..to
                }.mapNotNull { it.number("return_1d") }
        if (returns.isEmpty()) throw ApiFailure(500, "백테스팅 실행 실패: 해당 조건의 데이터가 없습니다")
        return simpleBacktest(returns, types.size, Duration.between(from, to).toDays())
    }

    @GetMapping("/monitoring/dashboard")
    fun dashboard(): Map<String, Any?> =
        mapOf(
            "summary" to tracking.summary(true),
            "recent_signals" to
                repository
                    .query(
                        "SELECT s.signal_type,s.symbol,s.current_price,s.created_at,o.return_1d,o.is_complete FROM technical_signals s LEFT JOIN signal_outcomes o ON o.signal_id=s.id ORDER BY s.created_at DESC LIMIT 5",
                    ).map {
                        mapOf(
                            "signal_type" to it["signal_type"],
                            "symbol" to it["symbol"],
                            "price" to
                                it.number("current_price")?.takeIf { p ->
                                    p != 0.0
                                },
                            "created_at" to it["created_at"],
                            "return_1d" to it.number("return_1d")?.takeIf { r -> r != 0.0 },
                            "is_complete" to it["is_complete"]?.let { _ -> it.truth("is_complete") },
                        )
                    },
            "timestamp" to LocalDateTime.now().pythonIso(),
        )

    @PostMapping("/tracking/update")
    fun update(
        @RequestParam(name = "batch_size", defaultValue = "10") @Min(1) @Max(100) batchSize: Int,
    ): Map<String, Any?> {
        tracking.update(1, true)
        // Original handler looks up absent keys, so these counters remain zero.
        return mapOf("success" to true, "processed_count" to 0, "updated_count" to 0, "message" to "수동 업데이트 완료")
    }

    companion object {
        private fun parseDate(value: String): LocalDateTime =
            if (value.length ==
                10
            ) {
                LocalDate.parse(value).atStartOfDay()
            } else {
                LocalDateTime.parse(value)
            }

        fun simpleBacktest(
            values: List<Double>,
            typeCount: Int,
            days: Long,
        ): Map<String, Any?> {
            val avg = values.average()
            val deviation = sqrt(values.sumOf { (it - avg) * (it - avg) } / values.size)
            val total = avg * values.size
            return mapOf(
                "strategy_name" to "Combined_${typeCount}_signals",
                "total_return" to total,
                "annual_return" to
                    if (days >
                        0
                    ) {
                        total * 365 / days
                    } else {
                        0.0
                    },
                "max_drawdown" to abs(values.min()),
                "sharpe_ratio" to
                    if (deviation >
                        0
                    ) {
                        avg / deviation
                    } else {
                        0.0
                    },
                "win_rate" to values.count { it > 0 } * 100.0 / values.size,
                "total_trades" to values.size,
            )
        }
    }
}

@RestController
@RequestMapping("/api/technical-analysis/signals/outcomes")
class SignalOutcomeController(
    private val tracking: OutcomeTrackingService,
) {
    @PostMapping("/track/{signal_id}")
    fun track(
        @PathVariable("signal_id") id: Long,
    ) = tracking.initialize(id)

    @PutMapping("/update")
    fun update(
        @RequestParam(name = "hours_old", defaultValue = "1") @Min(1) @Max(24) hours: Int,
    ) = tracking.update(hours)

    @GetMapping("/{signal_id}")
    fun details(
        @PathVariable("signal_id") id: Long,
    ) = tracking.details(id)

    @PostMapping("/test/{signal_id}")
    fun test(
        @PathVariable("signal_id") id: Long,
    ) = tracking.test(id)
}
