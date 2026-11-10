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

@SpringBootTest
@AutoConfigureMockMvc
class PriceSummaryIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getenv("MIGRATION_TEST_DATABASE_URL")
                    ?: "jdbc:h2:mem:prices;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            }
            registry.add("spring.datasource.username") { System.getenv("MIGRATION_TEST_DATABASE_USER") ?: "sa" }
            registry.add("spring.datasource.password") { System.getenv("MIGRATION_TEST_DATABASE_PASSWORD") ?: "" }
        }
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun prepare() {
        jdbc.execute("DROP TABLE IF EXISTS price_snapshots")
        jdbc.execute("DROP TABLE IF EXISTS price_high_records")
        jdbc.execute(
            "CREATE TABLE price_snapshots(id BIGINT PRIMARY KEY, symbol VARCHAR(20), source VARCHAR(30), close DOUBLE, high DOUBLE, low DOUBLE, volume DOUBLE, snapshot_at DATETIME, created_at DATETIME)",
        )
        jdbc.execute(
            "CREATE TABLE price_high_records(id BIGINT PRIMARY KEY, symbol VARCHAR(20), source VARCHAR(30), price DOUBLE, recorded_at DATETIME, created_at DATETIME)",
        )
        jdbc.update(
            "INSERT INTO price_snapshots(id,symbol,source,close,high,low,snapshot_at) VALUES(1,'AAPL','yahoo',100,110,90,'2025-01-01 12:00:00'),(2,'AAPL','yahoo',120,NULL,NULL,'2025-01-02 15:00:00'),(3,'AAPL','yahoo',NULL,125,115,'2025-01-02 10:00:00')",
        )
        jdbc.update(
            "INSERT INTO price_high_records(id,symbol,source,price,recorded_at) VALUES(1,'AAPL','yahoo',200,'2024-01-01 12:00:00'),(2,'AAPL','yahoo',190,'2025-01-02 12:00:00')",
        )
    }

    @Test
    fun `snapshot summary looks up nonnull high and low on latest snapshot day`() {
        mvc.get("/api/v2/market-price/snapshots/summary?symbols=AAPL").andExpect {
            status { isOk() }
            jsonPath("$.results.AAPL.latest_close") { value(120.0) }
            jsonPath("$.results.AAPL.previous_high") { value(125.0) }
            jsonPath("$.results.AAPL.previous_low") { value(115.0) }
            jsonPath("$.results.AAPL.has_complete_data") { value(true) }
        }
    }

    @Test
    fun `high record uses highest price rather than latest recorded date`() {
        mvc.get("/api/v2/market-price/high-records/summary?symbols=AAPL&symbols=MISSING").andExpect {
            status { isOk() }
            jsonPath("$.results.AAPL.price") { value(200.0) }
            jsonPath("$.results.MISSING.has_record") { value(false) }
            jsonPath("$.results.MISSING.error") { value("최고가 기록 없음") }
        }
    }

    @Test
    fun `default batch uses actual two original index symbols`() {
        mvc.get("/api/v2/market-price/snapshots/summary").andExpect {
            status { isOk() }
            jsonPath("$.total_symbols") { value(2) }
            jsonPath("$.results['^IXIC'].has_complete_data") { value(false) }
            jsonPath("$.results['^GSPC'].has_complete_data") { value(false) }
        }
        mvc.get("/api/v2/market-price/high-records/summary?batch_size=0").andExpect { status { isUnprocessableContent() } }
    }
}
