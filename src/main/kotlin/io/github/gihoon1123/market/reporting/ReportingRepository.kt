package io.github.gihoon1123.market.reporting

import io.github.gihoon1123.market.pythonIso
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class ReportingRepository(
    private val jdbc: JdbcTemplate,
) {
    fun latestPrice(symbol: String): ReportData {
        val fallback = mapOf("current_price" to 0.0, "change_amount" to 0.0, "change_percent" to 0.0, "volume" to 0, "timestamp" to null)
        return runCatching {
            jdbc
                .query("SELECT close,volume,snapshot_at FROM price_snapshots WHERE symbol=? ORDER BY snapshot_at DESC LIMIT 1", { row, _ ->
                    mapOf(
                        "current_price" to (row.getBigDecimal("close")?.toDouble() ?: 0.0),
                        "change_amount" to 0.0,
                        "change_percent" to 0.0,
                        "volume" to (row.getObject("volume") as? Number)?.toLong().let { it ?: 0L },
                        "timestamp" to row.getTimestamp("snapshot_at")?.toLocalDateTime()?.pythonIso(),
                    )
                }, symbol)
                .firstOrNull() ?: fallback
        }.getOrDefault(fallback)
    }

    fun dailyPriceCount(
        symbol: String,
        start: LocalDate,
        end: LocalDate,
    ): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM daily_prices WHERE symbol=? AND date>=? AND date<=?",
            Long::class.java,
            symbol,
            start,
            end,
        )
            ?: 0L

    fun latestClusters(symbol: String): List<ReportData> =
        jdbc.query(
            "SELECT cluster_name,pattern_count,avg_success_rate,bullish_tendency,bearish_tendency " +
                "FROM pattern_clusters WHERE symbol=? AND timeframe='1day' AND clustered_at=" +
                "(SELECT MAX(clustered_at) FROM pattern_clusters WHERE symbol=? AND timeframe='1day') ORDER BY cluster_id",
            { row, _ ->
                mapOf(
                    "name" to row.getString("cluster_name"),
                    "pattern_count" to row.getInt("pattern_count"),
                    "success_rate" to (row.getBigDecimal("avg_success_rate")?.toDouble()?.takeIf { it != 0.0 } ?: 50.0),
                    "bullish_tendency" to (row.getBigDecimal("bullish_tendency")?.toDouble() ?: 0.0),
                    "bearish_tendency" to (row.getBigDecimal("bearish_tendency")?.toDouble() ?: 0.0),
                )
            },
            symbol,
            symbol,
        )

    fun patternSummary(symbol: String): ReportData {
        val sql =
            "SELECT pattern_name,COUNT(*) AS total_count,AVG(pattern_duration_hours) AS avg_duration " +
                "FROM signal_patterns WHERE symbol=? GROUP BY pattern_name ORDER BY total_count DESC"
        val rows =
            jdbc
                .query(sql, {
                    row,
                    _,
                    ->
                    mapOf(
                        "name" to row.getString("pattern_name"),
                        "count" to row.getLong("total_count"),
                        "duration" to row.getBigDecimal("avg_duration")?.toDouble(),
                    )
                }, symbol)
        if (rows.isEmpty()) return mapOf("message" to "분석할 패턴이 없습니다.", "symbol" to symbol)
        val most = rows.maxBy { (it["count"] as Number).toLong() }
        val durations = rows.mapNotNull { (it["duration"] as? Number)?.toDouble()?.takeIf { n -> n != 0.0 } }
        return mapOf(
            "summary" to
                mapOf(
                    "total_patterns" to rows.size,
                    "total_occurrences" to rows.sumOf { (it["count"] as Number).toLong() },
                    "overall_avg_duration_hours" to if (durations.isEmpty()) 0.0 else durations.average(),
                    "most_frequent_pattern" to mapOf("name" to most["name"], "occurrences" to most["count"]),
                ),
            "symbol" to symbol,
            "analysis_timestamp" to
                java.time.LocalDateTime
                    .now(java.time.ZoneOffset.UTC)
                    .pythonIso(),
        )
    }
}
