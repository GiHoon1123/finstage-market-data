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
class CompanyIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getenv("MIGRATION_TEST_DATABASE_URL")
                    ?: "jdbc:h2:mem:company;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            }
            registry.add("spring.datasource.username") { System.getenv("MIGRATION_TEST_DATABASE_USER") ?: "sa" }
            registry.add("spring.datasource.password") { System.getenv("MIGRATION_TEST_DATABASE_PASSWORD") ?: "" }
        }
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun prepareDatabase() {
        for (table in listOf("income_statements", "balance_sheets", "cash_flow_statements", "symbols")) {
            jdbc.execute("DROP TABLE IF EXISTS $table")
        }
        jdbc.execute(
            "CREATE TABLE symbols(id INT PRIMARY KEY, symbol VARCHAR(10), name VARCHAR(255), country VARCHAR(100), korea_name VARCHAR(255))",
        )
        jdbc.execute(
            "CREATE TABLE income_statements(id INT PRIMARY KEY, symbol VARCHAR(16), date DATE, revenue DOUBLE, cost_of_revenue DOUBLE, gross_profit DOUBLE, operating_income DOUBLE, net_income DOUBLE, eps DOUBLE)",
        )
        jdbc.execute(
            "CREATE TABLE balance_sheets(id INT PRIMARY KEY, symbol VARCHAR(16), date DATE, total_assets DOUBLE, total_liabilities DOUBLE, total_equity DOUBLE, cash_and_equivalents DOUBLE, inventory DOUBLE)",
        )
        jdbc.execute(
            "CREATE TABLE cash_flow_statements(id INT PRIMARY KEY, symbol VARCHAR(16), date DATE, operating_cash_flow DOUBLE, investing_cash_flow DOUBLE, financing_cash_flow DOUBLE, free_cash_flow DOUBLE)",
        )
        jdbc.update("INSERT INTO symbols VALUES (1, 'MSFT', 'Microsoft', 'US', NULL), (2, 'AAPL', 'Apple', 'US', '애플')")
        jdbc.update(
            "INSERT INTO income_statements(id, symbol, date, revenue) VALUES (1, 'AAPL', '2024-12-31', 100.5), (2, 'AAPL', '2025-12-31', 200.5)",
        )
    }

    @Test
    fun `pagination keeps original duplicated route and JSON shape`() {
        mvc.get("/api/symbols/symbols?page=1&size=1").andExpect {
            status { isOk() }
            jsonPath("$.status") { value(200) }
            jsonPath("$.message") { value("심볼 목록 조회가 완료되었습니다") }
            jsonPath("$.data.items[0].symbol") { value("AAPL") }
            jsonPath("$.data.items[0].korea_name") { value("애플") }
            jsonPath("$.data.pagination.total_pages") { value(2) }
            jsonPath("$.data.pagination.has_next") { value(true) }
        }
    }

    @Test
    fun `missing symbol preserves successful null response`() {
        mvc.get("/api/financials/financials/UNKNOWN").andExpect {
            status { isOk() }
            content { json("""{"status":200,"message":"UNKNOWN 재무제표 조회가 완료되었습니다","data":null}""") }
        }
    }

    @Test
    fun `financials are sorted newest first and retain numeric and date fields`() {
        mvc.get("/api/financials/financials/AAPL").andExpect {
            status { isOk() }
            jsonPath("$.data.income_statement[0].date") { value("2025-12-31") }
            jsonPath("$.data.income_statement[0].revenue") { value(200.5) }
            jsonPath("$.data.balance_sheet.length()") { value(0) }
        }
    }

    @Test
    fun `invalid pagination and symbol are rejected before query`() {
        for (url in listOf(
            "/api/symbols/symbols?page=0",
            "/api/symbols/symbols?size=101",
            "/api/symbols/symbols?page=no",
            "/api/financials/financials/aapl",
        )) {
            mvc.get(url).andExpect { status { isUnprocessableContent() } }
        }
    }
}
