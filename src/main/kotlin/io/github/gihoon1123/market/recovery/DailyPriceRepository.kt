package io.github.gihoon1123.market.recovery

import io.github.gihoon1123.market.TechnicalIndicators
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalDate

data class DailyPrice(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
) {
    fun candle(): TechnicalIndicators.Candle = TechnicalIndicators.Candle(high, low, close, volume?.toDouble() ?: 0.0)
}

@Repository
class DailyPriceRepository(
    private val jdbc: JdbcTemplate,
) {
    fun prices(
        symbol: String,
        start: LocalDate,
        end: LocalDate,
    ): List<DailyPrice> =
        jdbc.query(
            "SELECT * FROM daily_prices WHERE symbol=? AND date>=? AND date<=? ORDER BY date ASC",
            { rs, _ ->
                DailyPrice(
                    rs.getDate("date").toLocalDate(),
                    rs.getDouble("open_price"),
                    rs.getDouble("high_price"),
                    rs.getDouble("low_price"),
                    rs.getDouble("close_price"),
                    rs.getObject("volume")?.let { (it as Number).toLong() },
                )
            },
            symbol,
            start,
            end,
        )

    fun save(
        symbol: String,
        price: DailyPrice,
        previousClose: Double?,
    ): Boolean {
        val change = previousClose?.let { price.close - it }
        val percent = if (previousClose == null || previousClose == 0.0) null else change!! / previousClose * 100
        return try {
            jdbc.update(
                "INSERT INTO daily_prices(symbol,date,open_price,high_price,low_price,close_price,volume,price_change,price_change_percent,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                symbol,
                price.date,
                price.open,
                price.high,
                price.low,
                price.close,
                price.volume,
                change,
                percent,
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }
    }

    fun count(symbol: String): Long = jdbc.queryForObject("SELECT COUNT(*) FROM daily_prices WHERE symbol=?", Long::class.java, symbol) ?: 0

    fun range(symbol: String): Map<String, String?> =
        jdbc
            .query(
                "SELECT MIN(date),MAX(date) FROM daily_prices WHERE symbol=?",
                { rs, _ -> mapOf("start_date" to rs.getDate(1)?.toString(), "end_date" to rs.getDate(2)?.toString()) },
                symbol,
            ).first()

    fun missingDates(
        symbol: String,
        start: LocalDate,
        end: LocalDate,
    ): List<LocalDate> {
        val existing = prices(symbol, start, end).map { it.date }.toSet()
        return start
            .datesUntil(end.plusDays(1))
            .filter {
                it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && it !in existing
            }.toList()
    }
}
