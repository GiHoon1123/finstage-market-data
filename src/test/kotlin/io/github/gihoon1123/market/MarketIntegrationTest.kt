package io.github.gihoon1123.market

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
                                                        "timestamp" to (0 until 40).map { 1700000000L + it * 86400L },
                                                        "indicators" to
                                                            mapOf(
                                                                "quote" to
                                                                    listOf(
                                                                        mapOf(
                                                                            "open" to (0 until 40).map { 100.0 + it },
                                                                            "high" to (0 until 40).map { 102.0 + it },
                                                                            "low" to (0 until 40).map { 98.0 + it },
                                                                            "close" to (0 until 40).map { 100.0 + it },
                                                                            "volume" to (0 until 40).map { 1000.0 },
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
