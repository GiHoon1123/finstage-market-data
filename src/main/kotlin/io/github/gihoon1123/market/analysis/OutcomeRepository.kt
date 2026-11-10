package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.pythonIso
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp

internal fun row(rs: ResultSet): Map<String, Any?> =
    (1..rs.metaData.columnCount).associate { index ->
        rs.metaData.getColumnLabel(index).lowercase() to
            when (val value = rs.getObject(index)) {
                is Timestamp -> value.toLocalDateTime().pythonIso()
                is BigDecimal -> value.toDouble()
                else -> value
            }
    }

internal fun Map<String, Any?>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

internal fun Map<String, Any?>.truth(key: String): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> false
    }

@Repository
class OutcomeRepository(
    private val jdbc: JdbcTemplate,
) {
    fun query(
        sql: String,
        vararg args: Any?,
    ): List<Map<String, Any?>> = jdbc.query(sql, { rs, _ -> row(rs) }, *args)

    fun execute(
        sql: String,
        vararg args: Any?,
    ): Int = jdbc.update(sql, *args)

    fun joined(): List<Map<String, Any?>> =
        query(
            "SELECT o.*,s.signal_type,s.symbol,s.timeframe,s.triggered_at,s.current_price,s.created_at AS signal_created_at FROM signal_outcomes o JOIN technical_signals s ON s.id=o.signal_id",
        )

    fun signal(id: Long): Map<String, Any?>? = query("SELECT * FROM technical_signals WHERE id=?", id).firstOrNull()

    fun outcome(id: Long): Map<String, Any?>? = query("SELECT * FROM signal_outcomes WHERE signal_id=?", id).firstOrNull()

    fun update(
        id: Long,
        values: Map<String, Any?>,
    ) {
        if (values.isEmpty()) return
        val allowed =
            horizons.flatMap { listOf("price_${it}_after", "return_$it") }.toSet() +
                setOf(
                    "is_successful_1h",
                    "is_successful_1d",
                    "is_successful_1w",
                    "is_successful_1m",
                    "is_complete",
                    "last_updated_at",
                    "updated_at",
                    "max_return",
                    "min_return",
                )
        require(values.keys.all { it in allowed })
        execute("UPDATE signal_outcomes SET ${values.keys.joinToString(",") { "$it=?" }} WHERE id=?", *(values.values + id).toTypedArray())
    }

    companion object {
        val horizons = listOf("1h", "4h", "1d", "1w", "1m")
    }
}
