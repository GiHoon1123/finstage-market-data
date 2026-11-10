package io.github.gihoon1123.market

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.sql.Timestamp

@Repository
class CompanyRepository(
    private val jdbc: JdbcTemplate,
) {
    fun countSymbols(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM symbols", Long::class.java) ?: 0L

    fun symbols(
        offset: Long,
        size: Int,
    ): List<Map<String, Any?>> =
        jdbc.query(
            "SELECT symbol, name, country, korea_name FROM symbols ORDER BY symbol LIMIT ? OFFSET ?",
            { rs, _ ->
                linkedMapOf(
                    "symbol" to rs.getString("symbol"),
                    "name" to rs.getString("name"),
                    "country" to rs.getString("country"),
                    "korea_name" to rs.getString("korea_name"),
                )
            },
            size,
            offset,
        )

    @Transactional(readOnly = true)
    fun statements(symbol: String): Map<String, List<Map<String, Any?>>>? {
        val exists = jdbc.queryForObject("SELECT COUNT(*) FROM symbols WHERE symbol = ?", Long::class.java, symbol) ?: 0L
        if (exists == 0L) return null
        return linkedMapOf(
            "income_statement" to rows("income_statements", symbol),
            "balance_sheet" to rows("balance_sheets", symbol),
            "cash_flow" to rows("cash_flow_statements", symbol),
        )
    }

    private fun rows(
        table: String,
        symbol: String,
    ): List<Map<String, Any?>> {
        require(table in setOf("income_statements", "balance_sheets", "cash_flow_statements"))
        return jdbc.query("SELECT * FROM $table WHERE symbol = ? ORDER BY date DESC", { rs, _ ->
            val metadata = rs.metaData
            (1..metadata.columnCount).associate { index ->
                val value = rs.getObject(index)
                metadata.getColumnLabel(index) to
                    when (value) {
                        is Date -> value.toLocalDate().toString()
                        is Timestamp -> value.toLocalDateTime().pythonIso()
                        else -> value
                    }
            }
        }, symbol)
    }
}
