package io.github.gihoon1123.market

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class SignalRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(
        symbol: String? = null,
        signalType: String? = null,
        timeframe: String? = null,
        start: LocalDateTime? = null,
        end: LocalDateTime? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        for ((column, value) in listOf("symbol" to symbol, "signal_type" to signalType, "timeframe" to timeframe)) {
            if (value != null) {
                conditions.add("$column = ?")
                parameters.add(value)
            }
        }
        if (start != null) {
            conditions.add("triggered_at >= ?")
            parameters.add(start)
        }
        if (end != null) {
            conditions.add("triggered_at <= ?")
            parameters.add(end)
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val page = if (limit == null) "" else " LIMIT ? OFFSET ?"
        if (limit != null) {
            parameters.add(limit)
            parameters.add(offset)
        }
        return jdbc.query(
            "SELECT * FROM technical_signals$where ORDER BY triggered_at DESC$page",
            { rs, _ -> signal(rs) },
            *parameters.toTypedArray(),
        )
    }

    fun strength(
        signalType: String,
        symbol: String?,
    ): Map<String, Any> {
        val parameters = mutableListOf<Any>(signalType)
        val filter =
            if (symbol == null) {
                ""
            } else {
                parameters.add(symbol)
                " AND symbol = ?"
            }
        return jdbc.queryForObject(
            "SELECT AVG(signal_strength) AS avg_value, MAX(signal_strength) AS max_value, MIN(signal_strength) AS min_value, COUNT(*) AS count_value FROM technical_signals WHERE signal_type = ? AND signal_strength IS NOT NULL$filter",
            { rs, _ ->
                mapOf(
                    "avg" to rs.getDouble("avg_value"),
                    "max" to rs.getDouble("max_value"),
                    "min" to rs.getDouble("min_value"),
                    "count" to rs.getLong("count_value"),
                )
            },
            *parameters.toTypedArray(),
        )
    }

    private fun signal(rs: ResultSet): Map<String, Any?> =
        linkedMapOf(
            "id" to rs.getLong("id"),
            "symbol" to rs.getString("symbol"),
            "signal_type" to rs.getString("signal_type"),
            "timeframe" to rs.getString("timeframe"),
            "triggered_at" to rs.getTimestamp("triggered_at")?.toLocalDateTime()?.toString(),
            "current_price" to rs.getBigDecimal("current_price")?.toDouble()?.takeIf { it != 0.0 },
            "indicator_value" to rs.getBigDecimal("indicator_value")?.toDouble()?.takeIf { it != 0.0 },
            "signal_strength" to rs.getBigDecimal("signal_strength")?.toDouble()?.takeIf { it != 0.0 },
            "volume" to rs.getObject("volume"),
            "market_condition" to rs.getString("market_condition"),
            "alert_sent" to rs.getObject("alert_sent")?.let { rs.getBoolean("alert_sent") },
            "created_at" to rs.getTimestamp("created_at")?.toLocalDateTime()?.toString(),
        )
}
