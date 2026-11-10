package io.github.gihoon1123.market

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

@RestController
@RequestMapping("/api/technical-analysis/signals")
class SignalController(
    private val repository: SignalRepository,
) {
    @GetMapping("/signals")
    fun recent(
        @RequestParam(required = false) symbol: String?,
        @RequestParam(name = "signal_type", required = false) signalType: String?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "24") @Min(1) @Max(168) hours: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): ApiResponse {
        val end = LocalDateTime.now(ZoneOffset.UTC)
        val start = end.minusHours(hours.toLong())
        val signals = repository.find(symbol, signalType, timeframe, start, end, limit)
        return ApiResponse(
            message = "기술적 신호 조회 완료 (${signals.size}개 신호)",
            data =
                mapOf(
                    "signals" to signals,
                    "metadata" to
                        mapOf(
                            "total_count" to signals.size,
                            "query_params" to
                                mapOf(
                                    "symbol" to symbol,
                                    "signal_type" to signalType,
                                    "timeframe" to timeframe,
                                    "hours" to hours,
                                    "limit" to limit,
                                ),
                            "time_range" to mapOf("start" to start.pythonIso(), "end" to end.pythonIso()),
                        ),
                ),
        )
    }

    @GetMapping("/signals/{symbol}")
    fun bySymbol(
        @PathVariable symbol: String,
        @RequestParam(name = "signal_type", required = false) signalType: String?,
        @RequestParam(required = false) timeframe: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
    ): ApiResponse {
        val signals =
            if (signalType != null) {
                repository.find(symbol, signalType, timeframe, limit = Math.addExact(limit, offset)).drop(offset).take(limit)
            } else {
                repository.find(symbol, limit = limit, offset = offset).filter { timeframe == null || it["timeframe"] == timeframe }
            }
        // The source applies its default 100-row cap when computing this total.
        val total = repository.find(symbol, limit = 100).count { timeframe == null || it["timeframe"] == timeframe }
        val page = offset / limit + 1
        val pages = total / limit + if (total % limit > 0) 1 else 0
        return ApiResponse(
            message = "$symbol 신호 조회 완료",
            data =
                mapOf(
                    "items" to
                        mapOf(
                            "symbol" to symbol,
                            "signals" to signals,
                            "filters" to mapOf("signal_type" to signalType, "timeframe" to timeframe),
                        ),
                    "pagination" to
                        mapOf(
                            "page" to page,
                            "size" to limit,
                            "total" to total,
                            "total_pages" to pages,
                            "has_next" to (page < pages),
                            "has_prev" to (page > 1),
                        ),
                ),
        )
    }

    @GetMapping("/stats/signal-count")
    fun counts(
        @RequestParam(defaultValue = "30") @Min(1) @Max(365) days: Int,
        @RequestParam(required = false) symbol: String?,
    ): ApiResponse {
        val end = LocalDateTime.now(ZoneOffset.UTC)
        val start = end.minusDays(days.toLong())
        val signals = repository.find(symbol, start = start, end = end)
        val stats = signals.groupingBy { it["signal_type"] }.eachCount().map { mapOf("signal_type" to it.key, "count" to it.value) }
        return ApiResponse(
            message = "신호 통계 조회 완료 (${signals.size}개 신호)",
            data =
                mapOf(
                    "statistics" to stats,
                    "metadata" to
                        mapOf(
                            "period" to mapOf("days" to days, "start_date" to start.pythonIso(), "end_date" to end.pythonIso()),
                            "filters" to mapOf("symbol" to symbol),
                            "total_signals" to signals.size,
                        ),
                ),
        )
    }

    @GetMapping("/stats/daily-count/{symbol}")
    fun daily(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "30") @Min(1) @Max(90) days: Int,
    ): ApiResponse {
        val signals = repository.find(symbol, start = LocalDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()))
        val counts =
            signals.groupingBy { it["triggered_at"].toString().substring(0, 10) }.eachCount().toSortedMap().map {
                mapOf(
                    "date" to it.key,
                    "count" to it.value,
                )
            }
        return ApiResponse(
            message = "$symbol 일별 신호 통계 조회 완료 (${signals.size}개 신호)",
            data =
                mapOf(
                    "symbol" to symbol,
                    "daily_counts" to counts,
                    "metadata" to
                        mapOf(
                            "period_days" to days,
                            "total_days_with_signals" to counts.size,
                            "total_signals" to signals.size,
                            "average_signals_per_day" to if (counts.isEmpty()) 0 else signals.size.toDouble() / counts.size,
                        ),
                ),
        )
    }

    @GetMapping("/stats/signal-strength/{signal_type}")
    fun strength(
        @PathVariable("signal_type") signalType: String,
        @RequestParam(required = false) symbol: String?,
    ): Map<String, Any?> {
        val stats = repository.strength(signalType, symbol)
        return mapOf(
            "signal_type" to signalType,
            "symbol" to symbol,
            "strength_statistics" to stats,
            "interpretation" to
                mapOf(
                    "avg" to "평균 신호 강도: ${format(stats["avg"])}%",
                    "max" to "최대 신호 강도: ${format(stats["max"])}%",
                    "min" to "최소 신호 강도: ${format(stats["min"])}%",
                    "sample_size" to "분석 대상 신호 개수: ${stats["count"]}개",
                ),
        )
    }

    private fun format(value: Any?): String = String.format(Locale.ROOT, "%.2f", value)
}
