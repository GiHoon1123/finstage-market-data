package io.github.gihoon1123.market.reporting

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.PriceSummaryRepository
import io.github.gihoon1123.market.analysis.AdvancedPatternService
import io.github.gihoon1123.market.analysis.PatternService
import io.github.gihoon1123.market.communications.TelegramAdapter
import io.github.gihoon1123.market.recovery.RecoveryService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DailyReportServiceTest {
    private val repository = mockk<ReportingRepository>()
    private val patterns = mockk<PatternService>()
    private val advanced = mockk<AdvancedPatternService>()
    private val telegram = mockk<TelegramAdapter>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val service =
        DailyReportService(
            repository,
            patterns,
            advanced,
            mockk<RecoveryService>(),
            mockk<PriceSummaryRepository>(),
            mockk<MarketDataClient>(),
            mockk<ReportNews>(),
            telegram,
            ReportText(clock),
            clock,
        )

    @Test
    fun `active daily report uses snapshots and source failure semantics`() {
        every { repository.latestPrice(any()) } returns mapOf("current_price" to 25000.0, "change_percent" to 0.0)
        every { repository.dailyPriceCount(any(), any(), any()) } returns 30
        every { patterns.discover(any(), "1day") } returns emptyMap()
        every { patterns.successful(any(), .6, 2) } returns emptyMap()
        every { repository.patternSummary(any()) } returns emptyMap()
        every { repository.latestClusters(any()) } returns
            listOf(
                mapOf(
                    "name" to "BB_상승_패턴",
                    "pattern_count" to 4,
                    "success_rate" to 75.0,
                    "bullish_tendency" to .6,
                    "bearish_tendency" to .8,
                ),
                mapOf(
                    "name" to "MACD_하락_패턴",
                    "pattern_count" to 5,
                    "success_rate" to 65.0,
                    "bullish_tendency" to .4,
                    "bearish_tendency" to .6,
                ),
            )
        val result = service.generateDailyReport()
        assertEquals("success", result["status"])
        assertEquals(false, result["telegram_sent"])
        val ixic = result.section("data").section("^IXIC")
        assertEquals(1, ixic.section("ml_analysis")["bullish_patterns"])
        assertEquals(1, ixic.section("ml_analysis")["bearish_patterns"])
        assertEquals(9L, ixic.section("ml_analysis")["total_patterns"])
        assertEquals("error", ixic.section("technical")["status"])
        assertEquals("^IXIC 데이터 부족 (최소 200일 필요)", ixic.section("signals").section("details")["error"])
        verify(exactly = 1) { telegram.send(any()) }
    }

    @Test
    fun `preview labels source demonstration metrics and counts Unicode code points`() {
        every { advanced.cluster(any(), 6, 5) } returns mapOf("message" to "insufficient patterns")
        val result = service.preview().section("preview")
        val message = result["report_message"].toString()
        assertTrue(message.startsWith("⚠️ 백테스팅·인사이트는 원본의 고정 시연값"))
        assertEquals(message.codePointCount(0, message.length), result["message_length"])
        assertEquals("source_demo_values", result.section("data_provenance")["backtesting"])
        verify(exactly = 0) { telegram.send(any()) }
    }

    @Test
    fun `preview retains dictionary cluster behavior`() {
        every { advanced.cluster(any(), 6, 5) } returns mapOf("clusters" to mapOf(0 to mapOf("pattern_count" to 4)))
        val result = service.previewMl()
        assertEquals(2, result["cluster_groups"])
        assertEquals(0L, result["bullish_patterns"])
        assertEquals("active", result["status"])
    }
}
