package io.github.gihoon1123.market

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class TechnicalUtilityTest {
    @Test
    fun `utility signal types preserve handler categories and total sixteen`() {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", ""))
        val mvc = MockMvcBuilders.standaloneSetup(TechnicalUtilityController(jdbc, JsonMapper.builder().build())).build()
        mvc.get("/api/technical-analysis/utils/signal-types").andExpect {
            status { isOk() }
            jsonPath("$.total_types") { value(16) }
            jsonPath("$.signal_types.rsi.RSI_oversold") { value("RSI 과매도 (30 이하)") }
            jsonPath("$.categories[0]") { value("moving_average") }
        }
    }

    @Test
    fun `health probes actual signals instead of a static connection status`() {
        val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute("CREATE TABLE technical_signals(id BIGINT,triggered_at TIMESTAMP)")
        jdbc.update(
            "INSERT INTO technical_signals VALUES(1,?),(2,?)",
            LocalDateTime.now(ZoneOffset.UTC),
            LocalDateTime.now(ZoneOffset.UTC).minusHours(2),
        )
        val mvc =
            MockMvcBuilders
                .standaloneSetup(TechnicalUtilityController(jdbc, JsonMapper.builder().build()))
                .setControllerAdvice(ApiExceptionHandler())
                .build()
        mvc.get("/api/technical-analysis/utils/health").andExpect {
            status { isOk() }
            jsonPath("$.recent_signals_1h") { value(1) }
            jsonPath("$.database") { value("connected") }
        }
        jdbc.execute("DROP TABLE technical_signals")
        mvc.get("/api/technical-analysis/utils/health").andExpect { status { isServiceUnavailable() } }
    }
}
