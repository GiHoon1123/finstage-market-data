package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.pythonIso
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

data class SignalPattern(
    val id: Long,
    val name: String,
    val type: String,
    val symbol: String,
    val timeframe: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val duration: Double?,
    val market: String?,
) {
    fun info(): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "start" to start.pythonIso(),
            "duration" to (duration ?: 0.0),
            "market_condition" to market,
        )
}

@Repository
class PatternRepository(
    private val jdbc: JdbcTemplate,
) {
    fun find(
        symbol: String? = null,
        name: String? = null,
        type: String? = null,
    ): List<SignalPattern> {
        val filters = mutableListOf<String>()
        val args = mutableListOf<Any>()
        listOf("symbol" to symbol, "pattern_name" to name, "pattern_type" to type).forEach { (field, value) ->
            if (value != null) {
                filters.add("$field = ?")
                args.add(value)
            }
        }
        val where = if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ")
        return jdbc.query("SELECT * FROM signal_patterns$where ORDER BY pattern_start DESC", { rs, _ ->
            SignalPattern(
                rs.getLong("id"),
                rs.getString("pattern_name"),
                rs.getString("pattern_type"),
                rs.getString("symbol"),
                rs.getString("timeframe"),
                rs.getTimestamp("pattern_start").toLocalDateTime(),
                rs.getTimestamp("pattern_end").toLocalDateTime(),
                rs.getBigDecimal("pattern_duration_hours")?.toDouble(),
                rs.getString("market_condition"),
            )
        }, *args.toTypedArray())
    }

    fun create(
        name: String,
        symbol: String,
        timeframe: String,
        ids: List<Long>,
        market: String?,
    ): SignalPattern {
        require(ids.size >= 2) { "순차적 패턴은 최소 2개의 신호가 필요합니다" }

        fun time(id: Long): LocalDateTime =
            jdbc
                .query(
                    "SELECT triggered_at FROM technical_signals WHERE id = ?",
                    { rs, _ -> rs.getTimestamp(1).toLocalDateTime() },
                    id,
                ).firstOrNull()
                ?: error("유효하지 않은 신호 ID가 포함되어 있습니다")
        val start = time(ids.first())
        val end = time(ids.last())
        val duration = Duration.between(start, end).toMillis() / 3600000.0
        val now = utcNow()
        jdbc.update(
            "INSERT INTO signal_patterns (pattern_name,pattern_type,symbol,timeframe,first_signal_id,second_signal_id,third_signal_id,fourth_signal_id,fifth_signal_id,pattern_start,pattern_end,pattern_duration_hours,market_condition,created_at,updated_at) VALUES (?,'sequential',?,?,?,?,?,?,?,?,?,?,?,?,?)",
            name,
            symbol,
            timeframe,
            ids.getOrNull(0),
            ids.getOrNull(1),
            ids.getOrNull(2),
            ids.getOrNull(3),
            ids.getOrNull(4),
            start,
            end,
            duration,
            market,
            now,
            now,
        )
        return find(symbol, name).first()
    }

    fun delete(ids: List<Long>): Int = ids.sumOf { jdbc.update("DELETE FROM signal_patterns WHERE id = ?", it) }
}
