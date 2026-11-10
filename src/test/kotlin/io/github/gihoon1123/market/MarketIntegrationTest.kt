package io.github.gihoon1123.market

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:market;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
    ],
)
@AutoConfigureMockMvc
class MarketIntegrationTest {
    companion object {
        private val requests = CopyOnWriteArrayList<String>()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v8/finance/chart/") { exchange ->
                    requests.add(exchange.requestURI.toASCIIString())
                    val requested = exchange.requestURI.path.substringAfterLast('/')
                    if (requested.startsWith("RATE_") && requests.count { "/$requested?" in it } <= 3) {
                        exchange.sendResponseHeaders(429, -1)
                        exchange.close()
                        return@createContext
                    }
                    val points = if (exchange.requestURI.path.endsWith("/ONE")) 1 else 40
                    val json =
                        if (exchange.requestURI.path.endsWith("/MISSING")) {
                            """{"chart":{"result":null,"error":{"code":"Not Found"}}}"""
                        } else {
                            val mapper = JsonMapper.builder().build()
                            mapper.writeValueAsString(
                                mapOf(
                                    "chart" to
                                        mapOf(
                                            "result" to
                                                listOf(
                                                    mapOf(
                                                        "meta" to mapOf("regularMarketPrice" to 200.5),
                                                        "timestamp" to (0 until points).map { 1700000000L + it * 86400L },
                                                        "indicators" to
                                                            mapOf(
                                                                "quote" to
                                                                    listOf(
                                                                        mapOf(
                                                                            "open" to (0 until points).map { 100.0 + it },
                                                                            "high" to (0 until points).map { 102.0 + it },
                                                                            "low" to (0 until points).map { 98.0 + it },
                                                                            "close" to (0 until points).map { 100.0 + it },
                                                                            "volume" to (0 until points).map { 1000.0 },
                                                                        ),
                                                                    ),
                                                            ),
                                                    ),
                                                ),
                                        ),
                                ),
                            )
                        }
                    val bytes = json.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun market(registry: DynamicPropertyRegistry) {
            registry.add("market.yahoo-base-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun closeServer() = server.stop(0)
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var market: MarketDataClient

    @Test
    fun `only synchronous Yahoo requests retry rate limits with a bounded policy`() {
        val client = MarketDataClient(JsonMapper.builder().build(), "http://127.0.0.1:${server.address.port}", 0)
        assertEquals(null, client.current("RATE_META"))
        assertEquals(1, requests.count { "/RATE_META?" in it })
        assertEquals(139.0, client.latestMinutePrice("RATE_QUOTE"))
        assertEquals(4, requests.count { "/RATE_QUOTE?" in it })
    }

    @Test
    fun `malformed JSON retains a validation response rather than Spring bad request envelope`() {
        mvc
            .post("/api/v2/technical-analysis/batch/indicators") {
                contentType = MediaType.APPLICATION_JSON
                content = "[broken"
            }.andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.detail[0].loc[0]") { value("body") }
            }
    }

    @Test
    fun `synchronous Yahoo minute quote remains distinct from async metadata price`() {
        assertEquals(200.5, market.current("QUOTE"))
        assertEquals(139.0, market.latestMinutePrice("QUOTE"))
        assertEquals(139.0, market.latestMinutePrice("QUOTE"))
        assertEquals(2, requests.count { "/QUOTE?" in it })
    }

    @Test
    fun `one valid candle is available even when comprehensive analysis needs two`() {
        mvc
            .post("/api/v2/technical-analysis/batch/indicators") {
                contentType = MediaType.APPLICATION_JSON
                content = """["ONE"]"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.analyzed_symbols") { value(0) }
            }
        mvc
            .post("/api/v2/technical-analysis/analysis/comprehensive") {
                contentType = MediaType.APPLICATION_JSON
                content = """["ONE"]"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.symbols_with_data") { value(1) }
                jsonPath("$.results.ONE.data_available") { value(true) }
            }
    }

    @Test
    fun `browser CORS preflight preserves configured credential support`() {
        mvc
            .options("/api/v2/technical-analysis/prices/batch") {
                header("Origin", "https://client.example")
                header("Access-Control-Request-Method", "GET")
            }.andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Origin", "https://client.example") }
                header { string("Access-Control-Allow-Credentials", "true") }
            }
    }

    @Test
    fun `current technical status validates source timeframes and computes actual series`() {
        mvc.get("/api/technical-analysis/signals/current-status/STATUS/15min").andExpect {
            status { isOk() }
            jsonPath("$.timeframe") { value("15min") }
            jsonPath("$.current_price") { value(139.0) }
            jsonPath("$.indicators.sma.5.39") { value(137.0) }
        }
        assertTrue(requests.any { "/STATUS?" in it && "range=5d" in it && "interval=15m" in it })
        mvc.get("/api/technical-analysis/signals/current-status/STATUS/3hour").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `batch analysis returns source full moving average series and partial data counts`() {
        mvc
            .post("/api/v2/technical-analysis/batch/indicators?batch_size=2") {
                contentType = MediaType.APPLICATION_JSON
                content = """["AAPL","MISSING"]"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.requested_symbols") { value(2) }
                jsonPath("$.analyzed_symbols") { value(1) }
                jsonPath("$.results.AAPL.indicators.sma.5.39") { value(137.0) }
                jsonPath("$.results.AAPL.indicators.sma.5.0") { doesNotExist() }
                jsonPath("$.results.AAPL.signals.macd") { value("bullish") }
            }
        mvc
            .post("/api/v2/technical-analysis/batch/indicators") {
                contentType = MediaType.APPLICATION_JSON
                content = """["MISSING"]"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `price monitor compares stored snapshots and highs without inventing zero category alerts`() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS price_snapshots(symbol VARCHAR(10),close DECIMAL(12,4),high DECIMAL(12,4),low DECIMAL(12,4),snapshot_at TIMESTAMP)",
        )
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS price_high_records(symbol VARCHAR(10),price DECIMAL(12,4),recorded_at TIMESTAMP,source VARCHAR(50))",
        )
        jdbc.update("DELETE FROM price_snapshots")
        jdbc.update("DELETE FROM price_high_records")
        jdbc.update("INSERT INTO price_snapshots VALUES ('MONITOR',200,220,180,CURRENT_TIMESTAMP)")
        jdbc.update("INSERT INTO price_high_records VALUES ('MONITOR',190,CURRENT_TIMESTAMP,'test')")
        mvc.get("/api/v2/technical-analysis/prices/monitor?symbols=MONITOR&alerts_only=true").andExpect {
            status { isOk() }
            jsonPath("$.monitored_symbols") { value(1) }
            jsonPath("$.total_alerts") { value(1) }
            jsonPath("$.results.MONITOR.alerts[0].type") { value("new_high") }
            jsonPath("$.results.MONITOR.analysis.prev_close.change_percent") { value(0.25) }
        }
    }

    @Test
    fun `background analysis status is tied to a real submitted job`() {
        val response =
            mvc
                .post("/api/v2/technical-analysis/analysis/background?symbols=AAPL&analysis_types=indicators")
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
        val id =
            JsonMapper
                .builder()
                .build()
                .readTree(response)
                .path("task_id")
                .asString()
        val deadline = System.nanoTime() + 5_000_000_000L
        var done = false
        while (System.nanoTime() < deadline) {
            val status =
                mvc
                    .get("/api/v2/technical-analysis/analysis/status/$id")
                    .andReturn()
                    .response.contentAsString
            if (JsonMapper
                    .builder()
                    .build()
                    .readTree(status)
                    .path("status")
                    .asString() == "completed"
            ) {
                done = true
                break
            }
            Thread.sleep(10)
        }
        assertTrue(done)
        mvc.get("/api/v2/technical-analysis/analysis/status/$id").andExpect {
            jsonPath("$.progress") { value(100) }
            jsonPath("$.result.task") { value("technical_analysis_batch") }
            jsonPath("$.result.results.AAPL.indicators.success") { value(true) }
        }
        mvc.get("/api/v2/technical-analysis/analysis/status/unknown").andExpect {
            jsonPath("$.status") { value("unknown") }
        }
    }

    @Test
    fun `legacy async price retains response model projection and caches source request`() {
        repeat(2) {
            mvc.get("/api/v2/async/symbols/CACHE/price").andExpect {
                status { isOk() }
                jsonPath("$.symbol") { value("CACHE") }
                jsonPath("$.current_price") { doesNotExist() }
                jsonPath("$.price") { doesNotExist() }
            }
        }
        assertEquals(1, requests.count { "/CACHE?" in it })
    }

    @Test
    fun `legacy async analysis caches calculated means and batch retains partial failure`() {
        mvc.get("/api/v2/async/technical/analysis/TECH").andExpect {
            status { isOk() }
            jsonPath("$.ma_20") { value(129.5) }
            jsonPath("$.ma_50") { value(119.5) }
            jsonPath("$.trend") { value("bullish") }
            jsonPath("$.source") { value("api") }
        }
        mvc.get("/api/v2/async/technical/analysis/TECH").andExpect {
            jsonPath("$.source") { value("cache") }
        }
        mvc.get("/api/v2/async/symbols/batch?symbols=AAPL,MISSING").andExpect {
            status { isOk() }
            jsonPath("$.prices.AAPL") { value(200.5) }
            jsonPath("$.prices.MISSING") { doesNotExist() }
        }
    }

    @Test
    fun `missing query produces FastAPI style required field error`() {
        mvc.get("/api/v2/async/symbols/batch").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].loc[0]") { value("query") }
            jsonPath("$.detail[0].type") { value("missing") }
        }
    }

    @Test
    fun `history preserves OHLCV arrays and original range query`() {
        mvc.get("/api/v2/market-price/history/AAPL?period=3mo&interval=1wk").andExpect {
            status { isOk() }
            jsonPath("$.data.history.close.length()") { value(40) }
            jsonPath("$.data.history.timestamps[0]") { value(1700000000L) }
            jsonPath("$.data.period") { value("3mo") }
            jsonPath("$.data.interval") { value("1wk") }
        }
        assertTrue(requests.any { "range=3mo" in it && "interval=1wk" in it && "includePrePost=true" in it })
    }

    @Test
    fun `missing history retains original nested error response`() {
        mvc.get("/api/v2/market-price/history/MISSING").andExpect {
            status { isNotFound() }
            jsonPath("$.detail.status") { value(404) }
            jsonPath("$.detail.message") { value("심볼 MISSING의 히스토리 데이터를 찾을 수 없습니다") }
        }
    }

    @Test
    fun `indicator output keeps keys and warmup is serialized as null`() {
        mvc.get("/api/v2/technical-analysis/indicators/AAPL").andExpect {
            status { isOk() }
            jsonPath("$.data_points") { value(40) }
            jsonPath("$.indicators.sma.5") { value(137.0) }
            jsonPath("$.indicators.rsi.current") { value(100.0) }
            jsonPath("$.indicators.macd.histogram") { exists() }
            jsonPath("$.indicators.sma.200") { doesNotExist() }
        }
    }

    @Test
    fun `indicator filters and missing history keep endpoint error shape`() {
        mvc.get("/api/v2/technical-analysis/indicators/AAPL?indicators=rsi").andExpect {
            status { isOk() }
            jsonPath("$.indicators.rsi") { exists() }
            jsonPath("$.indicators.sma") { doesNotExist() }
        }
        mvc.get("/api/v2/technical-analysis/indicators/MISSING").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("심볼 MISSING의 가격 데이터를 찾을 수 없습니다") }
        }
    }

    @Test
    fun `batch current prices retain partial failure and query-list contract`() {
        mvc.post("/api/v2/market-price/batch/current?symbols=AAPL&symbols=MISSING&batch_size=2").andExpect {
            status { isOk() }
            jsonPath("$.data.total_symbols") { value(2) }
            jsonPath("$.data.successful_count") { value(1) }
            jsonPath("$.data.results.AAPL") { value(200.5) }
        }
    }
}
