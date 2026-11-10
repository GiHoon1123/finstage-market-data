package io.github.gihoon1123.market

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime
import java.time.ZoneOffset

@SpringBootTest
@AutoConfigureMockMvc
class SignalIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getenv("MIGRATION_TEST_DATABASE_URL")
                    ?: "jdbc:h2:mem:signals;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            }
            registry.add("spring.datasource.username") { System.getenv("MIGRATION_TEST_DATABASE_USER") ?: "sa" }
            registry.add("spring.datasource.password") { System.getenv("MIGRATION_TEST_DATABASE_PASSWORD") ?: "" }
        }
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun prepareDatabase() {
        jdbc.execute("DROP TABLE IF EXISTS technical_signals")
        jdbc.execute(
            """
            CREATE TABLE technical_signals(
                id BIGINT PRIMARY KEY, symbol VARCHAR(20), signal_type VARCHAR(50), timeframe VARCHAR(10),
                triggered_at DATETIME, current_price DECIMAL(12,4), indicator_value DECIMAL(12,4),
                signal_strength DECIMAL(8,4), volume BIGINT, market_condition VARCHAR(20),
                additional_context TEXT, alert_sent BOOLEAN, alert_sent_at DATETIME,
                created_at DATETIME, updated_at DATETIME
            )
            """.trimIndent(),
        )
        val now = LocalDateTime.now(ZoneOffset.UTC)
        insert(1, "RSI_overbought", "1day", now.minusMinutes(1), 2.0)
        insert(2, "RSI_overbought", "15min", now.minusMinutes(2), 4.0)
        insert(3, "MA20_breakout_up", "1day", now.minusHours(30), 0.0)
    }

    @Test
    fun `recent signals apply time and type filters with original zero-null conversion`() {
        mvc.get("/api/technical-analysis/signals/signals?symbol=AAPL&timeframe=1day").andExpect {
            status { isOk() }
            jsonPath("$.data.signals.length()") { value(1) }
            jsonPath("$.data.signals[0].id") { value(1) }
            jsonPath("$.data.signals[0].indicator_value") { doesNotExist() }
            jsonPath("$.data.signals[0].alert_sent") { value(false) }
            jsonPath("$.data.metadata.total_count") { value(1) }
        }
    }

    @Test
    fun `symbol query preserves source pagination then timeframe filtering`() {
        mvc.get("/api/technical-analysis/signals/signals/AAPL?limit=1&offset=1&timeframe=1day").andExpect {
            status { isOk() }
            jsonPath("$.data.items.signals.length()") { value(0) }
            jsonPath("$.data.pagination.total") { value(2) }
            jsonPath("$.data.pagination.page") { value(2) }
        }
    }

    @Test
    fun `strength skips nulls and reports numeric statistics`() {
        mvc.get("/api/technical-analysis/signals/stats/signal-strength/RSI_overbought?symbol=AAPL").andExpect {
            status { isOk() }
            jsonPath("$.strength_statistics.avg") { value(3.0) }
            jsonPath("$.strength_statistics.count") { value(2) }
            jsonPath("$.interpretation.avg") { value("평균 신호 강도: 3.00%") }
        }
    }

    @Test
    fun `counts and daily metadata reflect stored records`() {
        mvc.get("/api/technical-analysis/signals/stats/signal-count?symbol=AAPL&days=2").andExpect {
            status { isOk() }
            jsonPath("$.data.metadata.total_signals") { value(3) }
        }
        mvc.get("/api/technical-analysis/signals/stats/daily-count/AAPL?days=2").andExpect {
            status { isOk() }
            jsonPath("$.data.metadata.total_signals") { value(3) }
        }
        mvc.get("/api/technical-analysis/signals/signals?hours=169").andExpect { status { isUnprocessableContent() } }
    }

    private fun insert(
        id: Long,
        type: String,
        timeframe: String,
        triggeredAt: LocalDateTime,
        strength: Double,
    ) {
        jdbc.update(
            "INSERT INTO technical_signals(id,symbol,signal_type,timeframe,triggered_at,current_price,indicator_value,signal_strength,volume,market_condition,alert_sent,created_at) VALUES (?,'AAPL',?,?,?,100,0,?,1000,'bullish',FALSE,?)",
            id,
            type,
            timeframe,
            triggeredAt,
            strength,
            triggeredAt,
        )
    }
}
