package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.MarketCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

class OperationsStateTest {
    @Test
    fun `JDBC observation measures actual statements and flushes pending slow samples without recursive tracking`() {
        val telemetry = QueryTelemetry()
        val underlying = DriverManagerDataSource("jdbc:h2:mem:telemetry;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
        val observed = telemetry.postProcessAfterInitialization(underlying, "dataSource") as DataSource
        val jdbc = JdbcTemplate(observed)
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS slow_query_logs(id INTEGER AUTO_INCREMENT,query_hash VARCHAR(32),query_template TEXT,original_query TEXT,duration DOUBLE,affected_rows INTEGER,table_names VARCHAR(500),operation_type VARCHAR(20),created_at TIMESTAMP,execution_timestamp TIMESTAMP)",
        )
        telemetry.reset()
        assertEquals(42, jdbc.queryForObject("SELECT ?", Int::class.java, 42))
        val statistic = telemetry.statistics(10).single()
        assertEquals(1, statistic["execution_count"])
        assertTrue((statistic["total_duration"] as Double) >= 0)
        telemetry.record("SELECT * FROM example WHERE id=42", 1.25, 3)
        val operations = DatabaseOperations(jdbc, telemetry)
        assertEquals(1, operations.flush())
        val slow = operations.slow(1, 10, null, null, null)
        assertEquals(1, slow.size)
        assertEquals(1.25, (slow.single()["duration"] as Number).toDouble())
        assertEquals(2, (telemetry.summary()["summary"] as Map<*, *>)["total_queries"])
        assertEquals(1, operations.slowStats(1)["total_count"])
        assertEquals(0, operations.flush())
    }

    @Test
    fun `LRU eviction and explicit cache management preserve independent namespaces`() {
        val lru = OperationsCaches.LruCache(2)
        lru.set("one", 1, 60)
        lru.set("two", 2, 60)
        assertEquals(1, lru.get("one"))
        lru.set("three", 3, 60)
        assertNull(lru.get("two"))
        assertEquals(1, lru.get("one"))
        val cache = MarketCache()
        cache.set("real-api", 12)
        val registry = OperationsCaches(cache)
        registry.cache("news").set("headline", "text", 60)
        registry.clear("news")
        assertEquals(12, cache.get("real-api"))
        registry.clear(null)
        assertNull(cache.get("real-api"))
    }

    @Test
    fun `JVM measurements expose actual bytes and unavailable Python metrics as null`() {
        val measurements = RuntimeMeasurements()
        val memory = measurements.memory()
        assertNull(memory["rss_mb"])
        assertTrue((memory["jvm_used_mb"] as Double) > 0)
        assertNull(measurements.objects()["total_objects"])
        val report = measurements.optimize(false)
        assertNotNull(report["memory_freed_mb"])
        assertEquals(1, measurements.optimizations.size)
        assertEquals(1, IndexAnalysis.efficiency(0.0, 1.0, 0.0).compareTo(-1.0))
    }
}
