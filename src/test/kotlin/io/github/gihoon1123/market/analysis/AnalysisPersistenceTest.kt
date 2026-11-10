package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.MarketDataClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

class AnalysisPersistenceTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: OutcomeRepository
    private lateinit var tracking: OutcomeTrackingService
    private val market = mockk<MarketDataClient>()

    @BeforeEach
    fun setup() {
        val source = DriverManagerDataSource("jdbc:h2:mem:analysis;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
        jdbc = JdbcTemplate(source)
        jdbc.execute("DROP ALL OBJECTS")
        jdbc.execute(
            "CREATE TABLE technical_signals(id BIGINT PRIMARY KEY,symbol VARCHAR(20),signal_type VARCHAR(50),timeframe VARCHAR(10),triggered_at DATETIME,current_price DECIMAL(12,4),indicator_value DECIMAL(12,4),signal_strength DECIMAL(8,4),volume BIGINT,market_condition VARCHAR(20),alert_sent BOOLEAN,created_at DATETIME,updated_at DATETIME)",
        )
        val horizon = OutcomeRepository.horizons.joinToString(",") { "price_${it}_after DECIMAL(12,4),return_$it DECIMAL(8,4)" }
        val success = listOf("1h", "1d", "1w", "1m").joinToString(",") { "is_successful_$it BOOLEAN" }
        jdbc.execute(
            "CREATE TABLE signal_outcomes(id BIGINT AUTO_INCREMENT PRIMARY KEY,signal_id BIGINT UNIQUE,$horizon,$success,max_return DECIMAL(8,4),min_return DECIMAL(8,4),max_drawdown DECIMAL(8,4),volatility_1d DECIMAL(8,4),volatility_1w DECIMAL(8,4),trend_strength DECIMAL(8,4),last_updated_at DATETIME,is_complete BOOLEAN,created_at DATETIME,updated_at DATETIME)",
        )
        jdbc.execute(
            "CREATE TABLE signal_patterns(id BIGINT AUTO_INCREMENT PRIMARY KEY,pattern_name VARCHAR(100),pattern_type VARCHAR(20),symbol VARCHAR(20),timeframe VARCHAR(10),first_signal_id BIGINT,second_signal_id BIGINT,third_signal_id BIGINT,fourth_signal_id BIGINT,fifth_signal_id BIGINT,pattern_start DATETIME,pattern_end DATETIME,pattern_duration_hours DECIMAL(8,2),market_condition VARCHAR(20),created_at DATETIME,updated_at DATETIME)",
        )
        val time = utcNow().minusHours(25)
        for (id in 1..3) {
            jdbc.update(
                "INSERT INTO technical_signals(id,symbol,signal_type,timeframe,triggered_at,current_price,indicator_value,signal_strength,alert_sent,created_at) VALUES (?,'AAPL','RSI_oversold','1d',?,100,0,1,FALSE,?)",
                id,
                time.plusMinutes(id.toLong()),
                time,
            )
        }
        repository = OutcomeRepository(jdbc)
        tracking = OutcomeTrackingService(repository, market, TransactionTemplate(DataSourceTransactionManager(source)))
        every { market.latestMinutePrice(any()) } returns 110.0
    }

    @Test
    fun `tracking is idempotent and timed updates persist prices returns and directional success`() {
        val first = tracking.initialize(1)
        val second = tracking.initialize(1)
        assertEquals(first["outcome_id"], second["outcome_id"])
        val result = tracking.update(1)
        assertEquals(1, result["updated"])
        val stored = repository.outcome(1)!!
        assertEquals(10.0, stored.number("return_1d"))
        assertTrue(stored.truth("is_successful_1d"))
        assertEquals(null, stored["price_1w_after"])
        assertEquals("진행 중 (1주 미만)", tracking.details(1)["tracking_status"])
    }

    @Test
    fun `pattern insert binds every field and test cleanup only removes known names`() {
        val patterns = PatternRepository(jdbc)
        val pattern = patterns.create("custom", "AAPL", "1d", listOf(1L, 2L, 3L), "bullish")
        assertEquals("custom", pattern.name)
        assertEquals(.03, pattern.duration)
        val service =
            PatternService(
                patterns,
                io.github.gihoon1123.market
                    .SignalRepository(jdbc),
                market,
            )
        service.test("AAPL")
        assertEquals(4, patterns.find("AAPL").size)
        assertEquals(3, service.cleanup("AAPL")["deleted_count"])
        assertEquals("custom", patterns.find("AAPL").single().name)
    }

    @Test
    fun `not started outcome preserves nonerror response and zero price fields become null`() {
        assertEquals("미시작", tracking.details(2)["tracking_status"])
        val signal = tracking.details(2)["signal"] as Map<*, *>
        assertEquals(null, signal["indicator_value"])
    }
}
