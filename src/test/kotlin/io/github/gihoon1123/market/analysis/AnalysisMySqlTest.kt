package io.github.gihoon1123.market.analysis

import io.github.gihoon1123.market.MarketDataClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

@EnabledIfEnvironmentVariable(named = "ANALYSIS_MYSQL_URL", matches = ".+")
class AnalysisMySqlTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var patterns: PatternRepository
    private lateinit var outcomes: OutcomeRepository
    private lateinit var tracking: OutcomeTrackingService
    private val market = mockk<MarketDataClient>()

    @BeforeEach
    fun setup() {
        val source =
            DriverManagerDataSource(
                System.getenv("ANALYSIS_MYSQL_URL"),
                System.getenv("ANALYSIS_MYSQL_USER"),
                System.getenv("ANALYSIS_MYSQL_PASSWORD"),
            )
        jdbc = JdbcTemplate(source)
        jdbc.update("DELETE FROM signal_patterns")
        jdbc.update("DELETE FROM signal_outcomes")
        jdbc.update("DELETE FROM technical_signals")
        val time = utcNow().minusDays(2)
        for (id in 1..6) {
            jdbc.update(
                "INSERT INTO technical_signals(id,symbol,signal_type,timeframe,triggered_at,current_price,created_at,updated_at) VALUES (?,'AAPL','RSI_oversold','1d',?,100,?,?)",
                id,
                time.plusHours(id.toLong()),
                time,
                time,
            )
        }
        patterns = PatternRepository(jdbc)
        outcomes = OutcomeRepository(jdbc)
        tracking = OutcomeTrackingService(outcomes, market, TransactionTemplate(DataSourceTransactionManager(source)))
        every { market.latestMinutePrice(any()) } returns 110.0
    }

    @Test
    fun `source schema persists idempotent outcomes and computes directional daily return`() {
        assertEquals(tracking.initialize(1)["outcome_id"], tracking.initialize(1)["outcome_id"])
        assertEquals(1, tracking.update(1)["updated"])
        assertEquals(10.0, outcomes.outcome(1)!!.number("return_1d"))
        assertTrue(outcomes.outcome(1)!!.truth("is_successful_1d"))
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM signal_outcomes", Long::class.java))
    }

    @Test
    fun `source pattern foreign keys decimals and advanced analysis operate on real rows`() {
        for (id in 1..5) patterns.create("RSI_$id", "AAPL", "1d", listOf(id.toLong(), id + 1L), "bullish")
        val advanced = AdvancedPatternService(patterns, outcomes)
        val matrix = advanced.similarity("AAPL", "sequential")
        assertEquals(5, matrix["total_patterns"])
        assertEquals(1.0, ((matrix["similarity_matrix"] as List<*>)[0] as List<*>)[0])
        assertEquals(5, advanced.temporal("AAPL", "1d", 30)["total_patterns"])
        assertEquals(5, advanced.cluster("AAPL", 2, 5)["total_patterns"])
    }

    @Test
    fun `real HTTP serializes undefined no-loss factor as valid JSON null`() {
        tracking.initialize(1)
        tracking.update(1)
        val mvc = MockMvcBuilders.standaloneSetup(PerformanceController(PerformanceService(outcomes))).build()
        val response =
            mvc
                .get("/api/technical-analysis/signals/performance/RSI_oversold")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.risk_metrics.profit_factor") { doesNotExist() }
                }.andReturn()
                .response.contentAsString
        val tree = JsonMapper.builder().build().readTree(response)
        assertTrue(tree.path("risk_metrics").path("profit_factor").isNull)
        assertEquals(10.0, tree.path("risk_metrics").path("avg_return").asDouble())
    }
}
