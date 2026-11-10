package io.github.gihoon1123.market.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@EnabledIfEnvironmentVariable(named = "ANALYSIS_MYSQL_URL", matches = ".+")
class OperationsMySqlTest {
    @Test
    fun `real MySQL slow statement is observed persisted aggregated and cleaned`() {
        val source =
            DriverManagerDataSource(
                System.getenv("ANALYSIS_MYSQL_URL"),
                System.getenv("ANALYSIS_MYSQL_USER"),
                System.getenv("ANALYSIS_MYSQL_PASSWORD"),
            )
        val telemetry = QueryTelemetry()
        val observed = telemetry.postProcessAfterInitialization(source, "dataSource") as DataSource
        val jdbc = JdbcTemplate(observed)
        val operations = DatabaseOperations(jdbc, telemetry)
        operations.query("SELECT 1")
        assertEquals(0, telemetry.statistics(10).size)
        jdbc.queryForObject("SELECT SLEEP(1.1)", Int::class.java)
        assertEquals(1, telemetry.statistics(10).size)
        assertEquals(1, operations.flush())
        val rows = operations.slow(1, 10, 1.0, "select", null)
        assertEquals(1, rows.size)
        assertTrue((rows.single()["duration"] as Number).toDouble() >= 1)
        assertEquals(1, operations.slowStats(1)["total_count"])
        assertEquals(1, (operations.patterns(1)["most_frequent_patterns"] as List<*>).size)
        jdbc.update("UPDATE slow_query_logs SET execution_timestamp=DATE_SUB(NOW(),INTERVAL 31 DAY)")
        assertEquals(1, operations.cleanup(30))
    }

    @Test
    fun `real information schema indexes are analyzed and suggested DDL remains review only`() {
        val jdbc =
            JdbcTemplate(
                DriverManagerDataSource(
                    System.getenv("ANALYSIS_MYSQL_URL"),
                    System.getenv("ANALYSIS_MYSQL_USER"),
                    System.getenv("ANALYSIS_MYSQL_PASSWORD"),
                ),
            )
        val index = IndexAnalysis(DatabaseOperations(jdbc, QueryTelemetry()))
        val report = index.report()
        assertEquals(19, (report["summary"] as Map<*, *>)["total_tables"])
        assertTrue(((report["summary"] as Map<*, *>)["total_indexes"] as Int) > 0)
        val statements = index.sql("signal_outcomes")
        assertTrue(statements.any { "outcome_date" in it })
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name='idx_signal_outcomes_symbol_outcome_date'",
                Int::class.java,
            ),
        )
    }
}
