package io.github.gihoon1123.market

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class PriceSummaryRepository(
    private val jdbc: JdbcTemplate,
) {
    data class Snapshot(
        val close: Double?,
        val high: Double?,
        val low: Double?,
        val at: LocalDateTime,
    )

    data class HighRecord(
        val price: Double,
        val at: LocalDateTime,
        val source: String,
    )

    fun latestSnapshot(symbol: String): Snapshot? =
        jdbc
            .query(
                "SELECT close, high, low, snapshot_at FROM price_snapshots WHERE symbol = ? ORDER BY snapshot_at DESC LIMIT 1",
                {
                    rs,
                    _,
                    ->
                    Snapshot(
                        rs.getObject("close")?.let {
                            rs.getDouble("close")
                        },
                        rs.getObject("high")?.let {
                            rs.getDouble("high")
                        },
                        rs.getObject("low")?.let { rs.getDouble("low") },
                        rs.getTimestamp("snapshot_at").toLocalDateTime(),
                    )
                },
                symbol,
            ).firstOrNull()

    fun latestHigh(symbol: String): HighRecord? =
        jdbc
            .query(
                "SELECT price, recorded_at, source FROM price_high_records WHERE symbol = ? ORDER BY price DESC LIMIT 1",
                { rs, _ -> HighRecord(rs.getDouble("price"), rs.getTimestamp("recorded_at").toLocalDateTime(), rs.getString("source")) },
                symbol,
            ).firstOrNull()

    fun snapshotSummary(symbol: String): Map<String, Any?> {
        val latest = latestSnapshot(symbol)
        val high = previousValue(symbol, latest, "high")
        val low = previousValue(symbol, latest, "low")
        return mapOf(
            "symbol" to symbol,
            "latest_close" to latest?.close,
            "latest_snapshot_date" to latest?.at?.pythonIso(),
            "previous_high" to high,
            "previous_low" to low,
            "has_complete_data" to (latest?.close.nonzero() && high.nonzero() && low.nonzero()),
        )
    }

    fun highSummary(symbol: String): Map<String, Any?> {
        val record = latestHigh(symbol) ?: return mapOf("symbol" to symbol, "has_record" to false, "error" to "최고가 기록 없음")
        val days = Math.floorDiv(Duration.between(record.at, LocalDateTime.now(ZoneOffset.UTC)).seconds, 86400)
        return mapOf(
            "symbol" to symbol,
            "has_record" to true,
            "price" to record.price,
            "recorded_at" to record.at.pythonIso(),
            "source" to record.source,
            "days_since_high" to days,
        )
    }

    fun priceSummary(
        symbol: String,
        current: Double,
    ): Map<String, Any?> {
        val snapshot = latestSnapshot(symbol)
        val high = latestHigh(symbol)
        return mapOf(
            "symbol" to symbol,
            "current_price" to current,
            "prev_close" to snapshot?.close,
            "all_time_high" to high?.price,
            "prev_high" to previousValue(symbol, snapshot, "high"),
            "prev_low" to previousValue(symbol, snapshot, "low"),
            "change_from_close" to change(current, snapshot?.close),
            "change_from_high" to change(current, high?.price),
            "timestamp" to LocalDateTime.now(ZoneOffset.UTC).pythonIso(),
        )
    }

    private fun previousValue(
        symbol: String,
        latest: Snapshot?,
        column: String,
    ): Double? {
        if (latest == null) return null
        require(column in setOf("high", "low"))
        val start = latest.at.toLocalDate().atStartOfDay()
        // The source uses the latest snapshot's day, despite the method's name.
        val end = start.plusDays(1).minusNanos(1000)
        return jdbc
            .query(
                "SELECT $column FROM price_snapshots WHERE symbol = ? AND snapshot_at >= ? AND snapshot_at < ? AND $column IS NOT NULL ORDER BY snapshot_at DESC LIMIT 1",
                { rs, _ -> rs.getDouble(column) },
                symbol,
                start,
                end,
            ).firstOrNull()
    }

    private fun change(
        current: Double,
        previous: Double?,
    ): Map<String, Double>? =
        previous?.takeIf { it != 0.0 }?.let { mapOf("amount" to current - it, "percent" to (current - it) / it * 100) }

    private fun Double?.nonzero(): Boolean = this != null && this != 0.0
}
