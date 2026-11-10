package io.github.gihoon1123.market.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate
import java.time.LocalDateTime

@EnabledIfEnvironmentVariable(named = "ANALYSIS_MYSQL_URL", matches = ".+")
class ReportingMySqlTest {
    @Test
    fun `original tables return latest snapshot and only latest clustering run with zero-rate fallback`() {
        val source =
            DriverManagerDataSource(
                System.getenv("ANALYSIS_MYSQL_URL"),
                System.getenv("ANALYSIS_MYSQL_USER"),
                System.getenv("ANALYSIS_MYSQL_PASSWORD"),
            )
        val jdbc = JdbcTemplate(source)
        val symbol = "REPORT_JVM"
        val repository = ReportingRepository(jdbc)
        val time = LocalDateTime.of(2026, 11, 10, 0, 0)
        jdbc.update("DELETE FROM price_snapshots WHERE symbol=?", symbol)
        jdbc.update("DELETE FROM pattern_clusters WHERE symbol=?", symbol)
        try {
            assertEquals(0.0, repository.latestPrice(symbol)["current_price"])
            assertNull(repository.latestPrice(symbol)["timestamp"])
            jdbc.update(
                "INSERT INTO price_snapshots(symbol,source,close,volume,snapshot_at,created_at) VALUES (?,'fixture',100,10,?,?)",
                symbol,
                time.minusDays(1),
                time,
            )
            jdbc.update(
                "INSERT INTO price_snapshots(symbol,source,close,volume,snapshot_at,created_at) VALUES (?,'fixture',110,11,?,?)",
                symbol,
                time,
                time,
            )
            assertEquals(110.0, repository.latestPrice(symbol)["current_price"])
            assertEquals(11L, repository.latestPrice(symbol)["volume"])
            val sql =
                "INSERT INTO pattern_clusters(symbol,cluster_id,cluster_name,timeframe,pattern_count," +
                    "avg_success_rate,bullish_tendency,bearish_tendency,n_clusters_total,clustered_at) " +
                    "VALUES (?,?,?,'1day',?,?,?,0,2,?)"
            jdbc.update(sql, symbol, 0, "old", 100, 90.0, .9, time.minusDays(1))
            jdbc.update(sql, symbol, 0, "latest-zero-rate", 4, 0.0, .6, time)
            jdbc.update(sql, symbol, 1, "latest", 5, 65.0, .2, time)
            val rows = repository.latestClusters(symbol)
            assertEquals(listOf("latest-zero-rate", "latest"), rows.map { it["name"] })
            assertEquals(50.0, rows.first()["success_rate"])
            assertEquals(0L, repository.dailyPriceCount(symbol, LocalDate.of(2026, 10, 10), time.toLocalDate()))
        } finally {
            jdbc.update("DELETE FROM price_snapshots WHERE symbol=?", symbol)
            jdbc.update("DELETE FROM pattern_clusters WHERE symbol=?", symbol)
        }
    }
}
