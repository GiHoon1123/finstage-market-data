package io.github.gihoon1123.market.scheduling

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.PriceHistory
import io.github.gihoon1123.market.PriceSummaryRepository
import io.github.gihoon1123.market.communications.TelegramAdapter
import io.github.gihoon1123.market.recovery.DailyPriceRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

@EnabledIfEnvironmentVariable(named = "ANALYSIS_MYSQL_URL", matches = ".+")
class SchedulerMySqlTest {
    @Test fun `original MySQL price tables persist complete snapshot alert cooldown and daily deduplication`() {
        val jdbc =
            JdbcTemplate(
                DriverManagerDataSource(
                    System.getenv("ANALYSIS_MYSQL_URL"),
                    System.getenv("ANALYSIS_MYSQL_USER"),
                    System.getenv("ANALYSIS_MYSQL_PASSWORD"),
                ),
            )
        val symbol = "^IXIC"
        for (table in listOf(
            "price_alert_logs",
            "price_snapshots",
            "price_high_records",
            "daily_prices",
        )) {
            jdbc.update("DELETE FROM $table WHERE symbol=?", symbol)
        }
        val market = mockk<MarketDataClient>()
        val telegram = mockk<TelegramAdapter>()
        every { telegram.configured() } returns false
        val history =
            PriceHistory(
                symbol,
                "5d",
                "1d",
                listOf(1704067200L, 1704153600L, 1704240000L),
                listOf(90.0, 100.0, 101.0),
                listOf(100.0, 110.0, 105.0),
                listOf(80.0, 90.0, 95.0),
                listOf(90.0, 100.0, 101.0),
                listOf(1000.0, 1100.0, 1200.0),
            )
        every { market.history(symbol, any(), "1d", 3) } returns history
        every { market.latestMinutePrice(symbol, any()) } returns 120.0
        val jobs = ScheduledPriceJobs(market, jdbc, PriceSummaryRepository(jdbc), DailyPriceRepository(jdbc), telegram)
        jobs.snapshot(symbol, "high")
        jobs.snapshot(symbol, "close")
        jobs.snapshot(symbol, "low")
        jobs.snapshot(symbol, "close")
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM price_snapshots WHERE symbol=?", Long::class.java, symbol))
        assertEquals(100.0, PriceSummaryRepository(jdbc).latestSnapshot(symbol)!!.close)
        jobs.high(symbol)
        jobs.high(symbol)
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM price_high_records WHERE symbol=?", Long::class.java, symbol))
        jobs.monitor(symbol)
        jobs.monitor(symbol)
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM price_alert_logs WHERE symbol=?", Long::class.java, symbol))
        assertEquals(3, jobs.collectHistory(symbol, "1mo", true)["saved_count"])
        assertEquals(3, jobs.collectHistory(symbol, "1mo", true)["duplicate_count"])
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM daily_prices WHERE symbol=?", Long::class.java, symbol))
    }
}
