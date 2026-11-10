package io.github.gihoon1123.market.recovery

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.TechnicalIndicators
import io.github.gihoon1123.market.analysis.AdvancedPatternService
import io.github.gihoon1123.market.analysis.OutcomeTrackingService
import io.github.gihoon1123.market.analysis.PatternService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Service
class RecoveryService(
    private val prices: DailyPriceRepository,
    private val market: MarketDataClient,
    private val jdbc: JdbcTemplate,
    private val patterns: PatternService,
    private val advanced: AdvancedPatternService,
    private val outcomes: OutcomeTrackingService,
) {
    val symbols = listOf("^IXIC", "^GSPC")
    val analysisTypes = listOf("signals", "patterns", "outcomes", "clusters")

    fun historical(
        symbols: List<String>,
        years: Int,
        force: Boolean,
    ): Map<String, Map<String, Any?>> =
        symbols.associateWith { symbol ->
            val cutoff = LocalDate.now().minusDays(years * 365L)
            val existing = prices.count(symbol)
            if (existing > 0 && !force && prices.missingDates(symbol, cutoff, LocalDate.now()).isEmpty()) {
                mapOf("status" to "skipped", "message" to "데이터가 이미 완전합니다", "existing_count" to existing, "missing_count" to 0)
            } else {
                val data = download(symbol, minimumTimestamp = LocalDateTime.now().minusDays(years * 365L))
                if (data.isEmpty()) {
                    mapOf("status" to "failed", "message" to "Yahoo Finance에서 데이터를 가져올 수 없습니다", "error" to "empty_data")
                } else {
                    var saved = 0
                    var duplicates = 0
                    var errors = 0
                    data.forEachIndexed { index, row ->
                        try {
                            if (prices.save(
                                    symbol,
                                    row,
                                    if (saved >
                                        0
                                    ) {
                                        data.getOrNull(index - 1)?.close
                                    } else {
                                        null
                                    },
                                )
                            ) {
                                saved++
                            } else {
                                duplicates++
                            }
                        } catch (_: Exception) {
                            errors++
                        }
                    }
                    mapOf(
                        "status" to "completed",
                        "message" to "${saved}개 데이터 저장 완료",
                        "saved_count" to saved,
                        "duplicate_count" to duplicates,
                        "error_count" to errors,
                        "total_processed" to data.size,
                        "date_range" to mapOf("start" to data.first().date.toString(), "end" to data.last().date.toString()),
                    )
                }
            }
        }

    internal fun download(
        symbol: String,
        startYear: Int = 2000,
        endYear: Int = LocalDate.now().year,
        minimumTimestamp: LocalDateTime? = null,
    ): List<DailyPrice> {
        val found = sortedMapOf<Long, DailyPrice>()
        for (year in startYear..endYear) {
            val zone = ZoneId.systemDefault()
            val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toEpochSecond()
            val to =
                LocalDate
                    .of(year, 12, 31)
                    .atTime(23, 59, 59)
                    .atZone(zone)
                    .toEpochSecond()
            val chart =
                market.chart(symbol, mapOf("period1" to "$from", "period2" to "$to", "interval" to "1d"), rateLimitRetries = 3) ?: continue
            val quote = chart.path("indicators").path("quote").path(0)
            chart.path("timestamp").toList().forEachIndexed { index, time ->
                val values = listOf("open", "high", "low", "close", "volume").map { quote.path(it).path(index) }
                if (values.all { it.isNumber && it.asDouble().isFinite() }) {
                    val timestamp = time.asLong()
                    if (minimumTimestamp != null &&
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC).isBefore(minimumTimestamp)
                    ) {
                        return@forEachIndexed
                    }
                    found.putIfAbsent(
                        timestamp,
                        DailyPrice(
                            Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toLocalDate(),
                            values[0].asDouble(),
                            values[1].asDouble(),
                            values[2].asDouble(),
                            values[3].asDouble(),
                            values[4].asLong(),
                        ),
                    )
                }
            }
        }
        return found.values.toList()
    }

    fun technical(
        symbols: List<String>,
        types: List<String>,
        days: Int,
    ): Map<String, Map<String, Any?>> =
        symbols.associateWith { symbol ->
            analysisTypes.filter { it in types }.associateWith { type ->
                try {
                    when (type) {
                        "signals" -> signals(symbol, days)
                        "patterns" ->
                            patterns.discover(symbol, "1day").let {
                                mapOf(
                                    "status" to "completed",
                                    "patterns_discovered" to (it["discovered_patterns"] ?: 0),
                                    "patterns_saved" to (it["saved_patterns"] ?: 0),
                                )
                            }
                        "outcomes" -> {
                            val ids =
                                jdbc.query(
                                    "SELECT id FROM technical_signals WHERE symbol=? AND triggered_at>=? AND triggered_at<=?",
                                    { rs, _ -> rs.getLong(1) },
                                    symbol,
                                    LocalDateTime.now().minusDays(days.toLong()),
                                    LocalDateTime.now(),
                                )
                            ids.forEach(outcomes::initialize)
                            val updated = outcomes.update(1)
                            mapOf(
                                "status" to "completed",
                                "signals_processed" to ids.size,
                                "outcomes_created" to ids.size,
                                "outcomes_updated" to (updated["updated_count"] ?: 0),
                            )
                        }
                        "clusters" ->
                            advanced.cluster(symbol, 5, 10).let {
                                mapOf(
                                    "status" to "completed",
                                    "clusters_created" to (it["n_clusters"] ?: 0),
                                    "patterns_clustered" to (it["patterns_clustered"] ?: 0),
                                )
                            }
                        else -> throw IllegalArgumentException("유효하지 않은 분석 타입")
                    }
                } catch (_: Exception) {
                    mapOf("status" to "failed", "error" to "분석 처리 실패")
                }
            }
        }

    fun signals(
        symbol: String,
        days: Int,
    ): Map<String, Any> {
        val rows = prices.prices(symbol, LocalDate.now().minusDays(days.toLong()), LocalDate.now())
        if (rows.isEmpty()) return mapOf("status" to "failed", "message" to "일봉 데이터가 없습니다")
        val closes = rows.map { it.close }
        val ma20 = TechnicalIndicators.sma(closes, 20)
        val ma200 = TechnicalIndicators.sma(closes, 200)
        val rsi = TechnicalIndicators.rsi(closes)
        val bands = TechnicalIndicators.bollinger(closes)
        var generated = 0
        var saved = 0

        fun add(
            index: Int,
            type: String,
            value: Double,
            strength: Double,
        ) {
            generated++
            try {
                jdbc.update(
                    "INSERT INTO technical_signals(symbol,signal_type,timeframe,triggered_at,current_price,indicator_value,signal_strength,created_at,updated_at) VALUES (?,?,'1day',?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    symbol,
                    type,
                    rows[index].date.atStartOfDay(),
                    closes[index],
                    value,
                    strength,
                )
                saved++
            } catch (_: Exception) {
                // One failed row does not roll back previously committed source rows.
            }
        }
        for (i in 1 until rows.size) {
            for ((period, ma) in listOf(20 to ma20, 200 to ma200)) {
                if (closes[i - 1] <= ma[i - 1] &&
                    closes[i] > ma[i]
                ) {
                    add(i, "MA${period}_breakout_up", ma[i], (closes[i] - ma[i]) / ma[i] * 100)
                }
            }
        }
        for (i in 1 until rows.size) {
            if (rsi[i - 1] <= 70 && rsi[i] > 70) add(i, "RSI_overbought", rsi[i], rsi[i] - 70)
            if (rsi[i - 1] >= 30 && rsi[i] < 30) add(i, "RSI_oversold", rsi[i], 30 - rsi[i])
        }
        for (i in 1 until rows.size) {
            val upper = bands.getValue("upper")
            val lower = bands.getValue("lower")
            if (closes[i - 1] < upper[i - 1] &&
                closes[i] >= upper[i]
            ) {
                add(i, "BB_touch_upper", upper[i], (closes[i] - upper[i]) / upper[i] * 100)
            }
            if (closes[i - 1] > lower[i - 1] &&
                closes[i] <= lower[i]
            ) {
                add(i, "BB_touch_lower", lower[i], (lower[i] - closes[i]) / lower[i] * 100)
            }
        }
        return mapOf("status" to "completed", "signals_generated" to generated, "signals_saved" to saved, "data_points" to rows.size)
    }

    fun counts(): Map<String, Any?> {
        val tables = listOf("daily_prices", "technical_signals", "signal_patterns", "signal_outcomes", "pattern_clusters")
        val totals = tables.associate { "${it}_count" to count(it) }
        val breakdown = symbols.associateWith { symbol -> tables.take(3).associateWith { count(it, symbol) } }
        return totals + ("symbol_breakdown" to breakdown)
    }

    internal fun count(
        table: String,
        symbol: String? = null,
    ): Long {
        require(table in setOf("daily_prices", "technical_signals", "signal_patterns", "signal_outcomes", "pattern_clusters"))
        return if (symbol == null) {
            jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java) ?: 0
        } else {
            jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE symbol=?", Long::class.java, symbol) ?: 0
        }
    }

    fun summary(symbol: String?): Map<String, Map<String, Any?>> =
        (symbol?.let(::listOf) ?: symbols).associateWith {
            mapOf(
                "daily_prices_count" to prices.count(it),
                "date_range" to prices.range(it),
                "technical_signals_count" to count("technical_signals", it),
                "signal_patterns_count" to count("signal_patterns", it),
            )
        }

    @Transactional
    fun cleanup(types: List<String>): Map<String, Int> =
        types
            .mapNotNull { type ->
                when (type) {
                    "test_signals" -> type to jdbc.update("DELETE FROM technical_signals WHERE signal_type LIKE '%test%'")
                    "test_patterns" -> type to jdbc.update("DELETE FROM signal_patterns WHERE pattern_name LIKE '%test%'")
                    else -> null
                }
            }.toMap()
}
