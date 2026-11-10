package io.github.gihoon1123.market.scheduling

import com.sun.net.httpserver.HttpServer
import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.PriceSummaryRepository
import io.github.gihoon1123.market.communications.TelegramAdapter
import io.github.gihoon1123.market.recovery.DailyPriceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ScheduledPriceJobsTest {
    @Test fun `local HTTP snapshot merge ATH cooldown notification and history persistence follow source boundaries`() {
        val jdbc =
            JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:scheduled_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""))
        jdbc.execute(
            "CREATE TABLE price_snapshots(id BIGINT AUTO_INCREMENT PRIMARY KEY,symbol VARCHAR(20),source VARCHAR(30),close DOUBLE,high DOUBLE,low DOUBLE,snapshot_at TIMESTAMP,created_at TIMESTAMP)",
        )
        jdbc.execute(
            "CREATE TABLE price_high_records(id BIGINT AUTO_INCREMENT PRIMARY KEY,symbol VARCHAR(20),source VARCHAR(30),price DOUBLE,recorded_at TIMESTAMP,created_at TIMESTAMP)",
        )
        jdbc.execute(
            "CREATE TABLE price_alert_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,symbol VARCHAR(20),alert_type VARCHAR(30),base_type VARCHAR(30),base_price DOUBLE,current_price DOUBLE,threshold_percent DOUBLE,actual_percent DOUBLE,base_time TIMESTAMP,triggered_at TIMESTAMP)",
        )
        jdbc.execute(
            "CREATE TABLE daily_prices(id BIGINT AUTO_INCREMENT PRIMARY KEY,symbol VARCHAR(10),date DATE,open_price DOUBLE,high_price DOUBLE,low_price DOUBLE,close_price DOUBLE,volume BIGINT,price_change DOUBLE,price_change_percent DOUBLE,created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(symbol,date))",
        )
        val mapper = JsonMapper.builder().build()
        val messages = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v8/finance/chart/") { exchange ->
            requests += exchange.requestURI.toString()
            val json =
                mapper.writeValueAsString(
                    mapOf(
                        "chart" to
                            mapOf(
                                "result" to
                                    listOf(
                                        mapOf(
                                            "meta" to mapOf("regularMarketPrice" to 120.0),
                                            "timestamp" to listOf(1704067200L, 1704153600L, 1704240000L),
                                            "indicators" to
                                                mapOf(
                                                    "quote" to
                                                        listOf(
                                                            mapOf(
                                                                "open" to listOf(90, 100, 101),
                                                                "close" to
                                                                    if (exchange.requestURI.query.contains(
                                                                            "interval=1m",
                                                                        )
                                                                    ) {
                                                                        listOf(90, 100, 120)
                                                                    } else {
                                                                        listOf(90, 100, 101)
                                                                    },
                                                                "high" to listOf(100, 110, 105),
                                                                "low" to listOf(80, 90, 95),
                                                                "volume" to listOf(1000, 1100, 1200),
                                                            ),
                                                        ),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                )
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        server.createContext("/botlocal-token/sendMessage") { exchange ->
            messages += exchange.requestBody.bufferedReader().readText()
            val body = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val clock = Clock.fixed(Instant.parse("2024-01-04T12:00:00Z"), ZoneOffset.UTC)
            val jobs =
                ScheduledPriceJobs(
                    MarketDataClient(mapper, url),
                    jdbc,
                    PriceSummaryRepository(jdbc),
                    DailyPriceRepository(jdbc),
                    TelegramAdapter(mapper, true, url, "local-token", "local-chat"),
                    "test",
                    clock,
                )
            jobs.snapshot("^IXIC", "high")
            jobs.snapshot("^IXIC", "close")
            jobs.snapshot("^IXIC", "low")
            jobs.snapshot("^IXIC", "close")
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM price_snapshots", Long::class.java))
            val snapshot = PriceSummaryRepository(jdbc).latestSnapshot("^IXIC")!!
            assertEquals(100.0, snapshot.close)
            assertEquals(110.0, snapshot.high)
            assertEquals(90.0, snapshot.low)
            jobs.high("^IXIC")
            jobs.high("^IXIC")
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM price_high_records", Long::class.java))
            val first = jobs.monitor("^IXIC")["alerts"] as List<*>
            assertEquals(setOf("price_rise", "new_high", "break_prev_high"), first.toSet())
            assertEquals(3, messages.size)
            assertEquals(emptyList<String>(), jobs.monitor("^IXIC")["alerts"])
            assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM price_alert_logs", Long::class.java))
            val collected = jobs.collectHistory("^IXIC", "3mo", false)
            assertEquals(3, collected["data_points"])
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM daily_prices", Long::class.java))
            assertEquals(3, jobs.collectHistory("^IXIC", "1mo", true)["saved_count"])
            assertEquals(3, jobs.collectHistory("^IXIC", "1mo", true)["duplicate_count"])
            assertTrue(requests.any { it.contains("range=max") })
            assertTrue(requests.any { it.contains("range=5d") })
        } finally {
            server.stop(0)
        }
    }
}
