package io.github.gihoon1123.market.reporting

import io.github.gihoon1123.market.MarketDataClient
import io.github.gihoon1123.market.PriceSummaryRepository
import io.github.gihoon1123.market.analysis.AdvancedPatternService
import io.github.gihoon1123.market.analysis.PatternService
import io.github.gihoon1123.market.communications.TelegramAdapter
import io.github.gihoon1123.market.pythonIso
import io.github.gihoon1123.market.recovery.RecoveryService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class DailyReportService(
    private val repository: ReportingRepository,
    private val patterns: PatternService,
    private val advanced: AdvancedPatternService,
    private val recovery: RecoveryService,
    private val summaries: PriceSummaryRepository,
    private val market: MarketDataClient,
    private val news: ReportNews,
    private val telegram: TelegramAdapter,
    val text: ReportText,
    @Qualifier("reportingClock") private val clock: Clock,
) : DailyReports {
    private fun now() = LocalDateTime.now(clock).pythonIso()

    private fun error(failure: Exception): ReportData =
        mapOf(
            "status" to "error",
            "error" to (failure.message ?: failure.javaClass.simpleName),
        )

    override fun generateDailyReport(): ReportData {
        val today = LocalDate.now(clock)
        return try {
            val all =
                text.symbols.associateWith { symbol ->
                    mapOf(
                        "price" to repository.latestPrice(symbol),
                        "technical" to technical(symbol),
                        "signals" to signals(symbol, today),
                        "patterns" to patternSection(symbol),
                        "ml_analysis" to mlAnalysis(symbol),
                    )
                }
            val insights = text.insights(all)
            val message = text.daily(all, insights)
            runCatching { telegram.send(message) }
            // The source notifier returns None even on delivery success, so the active method reports false.
            mapOf(
                "status" to "success",
                "report_date" to today.toString(),
                "analyzed_symbols" to text.symbols,
                "telegram_sent" to false,
                "data" to all,
                "insights" to insights,
            )
        } catch (failure: Exception) {
            error(failure) + ("report_date" to today.toString())
        }
    }

    override fun comprehensive(symbol: String): ReportData {
        val today = LocalDate.now(clock)
        return try {
            val sections =
                linkedMapOf<String, Any?>(
                    "price_data" to priceSection(symbol),
                    "technical_analysis" to technical(symbol),
                    "signals" to signals(symbol, today),
                    "patterns" to patternSection(symbol),
                    "news" to newsSection(symbol),
                )
            sections["summary"] = summary(sections)
            mapOf("symbol" to symbol, "report_date" to today.toString(), "generated_at" to now(), "sections" to sections)
        } catch (failure: Exception) {
            mapOf(
                "symbol" to symbol,
                "report_date" to today.toString(),
                "generated_at" to now(),
                "error" to failure.message,
            )
        }
    }

    fun technical(symbol: String): ReportData {
        // The last Python definition calls a method that does not exist on TechnicalIndicatorService.
        return mapOf(
            "status" to "error",
            "error" to "'TechnicalIndicatorService' object has no attribute 'calculate_all_indicators'",
            "message" to "기술적 분석 실패",
        )
    }

    fun signals(
        symbol: String,
        date: LocalDate,
    ): ReportData =
        try {
            val start = date.minusDays(30)
            val count = repository.dailyPriceCount(symbol, start, date)
            val details: ReportData = if (count < 200) mapOf("error" to "$symbol 데이터 부족 (최소 200일 필요)") else recovery.signals(symbol, 30)
            mapOf(
                "summary" to
                    mapOf(
                        "total_signals" to (details["total_signals"] ?: 0),
                        "saved_signals" to (details["saved_signals"] ?: 0),
                        "signal_breakdown" to (details["signal_breakdown"] ?: emptyMap<String, Any>()),
                        "period" to mapOf("start" to start.toString(), "end" to date.toString()),
                    ),
                "details" to details,
                "status" to "success",
            )
        } catch (failure: Exception) {
            error(failure)
        }

    fun patternSection(symbol: String): ReportData =
        try {
            mapOf(
                "discovery" to patterns.discover(symbol, "1day"),
                "successful_patterns" to patterns.successful(symbol, .6, 2),
                "summary" to repository.patternSummary(symbol),
                "status" to "success",
            )
        } catch (failure: Exception) {
            error(failure)
        }

    fun priceSection(symbol: String): ReportData =
        try {
            val current = market.current(symbol)
            val price = if (current == null) mapOf("error" to "$symbol 가격 조회 실패") else summaries.priceSummary(symbol, current)
            mapOf(
                "current_price" to price,
                "snapshot" to summaries.snapshotSummary(symbol),
                "high_record" to summaries.highSummary(symbol),
                "status" to "success",
            )
        } catch (failure: Exception) {
            error(failure)
        }

    fun newsSection(symbol: String): ReportData {
        val sources =
            listOf("investing.com" to "investing", "yahoo.com" to "yahoo").associate { (source, key) ->
                key to
                    runCatching { news.summary(symbol, source) }.getOrElse { mapOf("error" to it.message) }
            }
        return mapOf("sources" to sources, "total_news" to sources.values.sumOf { it.count("total_news") }, "status" to "success")
    }

    fun mlAnalysis(symbol: String): ReportData =
        try {
            val clusters = repository.latestClusters(symbol)
            if (clusters.isEmpty()) {
                mapOf(
                    "status" to "no_data",
                    "message" to "클러스터링 데이터가 없습니다",
                    "cluster_groups" to 0,
                    "bullish_patterns" to 0,
                    "bearish_patterns" to 0,
                    "neutral_patterns" to 0,
                )
            } else {
                val bullish =
                    clusters.filter { it.amount("bullish_tendency") >= .6 }.map {
                        it.filterKeys { key ->
                            key != "bearish_tendency"
                        }
                    }
                val bearish =
                    clusters.filter { it.amount("bullish_tendency") < .6 && it.amount("bearish_tendency") >= .6 }.map {
                        it.filterKeys { key ->
                            key !=
                                "bullish_tendency"
                        }
                    }
                val neutral =
                    clusters.filter { it.amount("bullish_tendency") < .6 && it.amount("bearish_tendency") < .6 }.map {
                        it.filterKeys { key ->
                            key !in
                                setOf("bullish_tendency", "bearish_tendency")
                        }
                    }
                mapOf(
                    "status" to "success",
                    "cluster_groups" to clusters.size,
                    "total_patterns" to clusters.sumOf { it.count("pattern_count") },
                    "bullish_patterns" to bullish.size,
                    "bearish_patterns" to bearish.size,
                    "neutral_patterns" to neutral.size,
                    "bullish_clusters" to bullish,
                    "bearish_clusters" to bearish,
                    "neutral_clusters" to neutral,
                    "strongest_bullish" to bullish.maxByOrNull { it.amount("success_rate") * it.count("pattern_count") },
                    "strongest_bearish" to bearish.maxByOrNull { it.amount("success_rate") * it.count("pattern_count") },
                    "analysis_timestamp" to LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).pythonIso(),
                )
            }
        } catch (failure: Exception) {
            error(failure) +
                mapOf("cluster_groups" to 0, "bullish_patterns" to 0, "bearish_patterns" to 0, "neutral_patterns" to 0)
        }

    fun previewMl(): ReportData {
        var groups = 0
        var bullish = 0L
        var bearish = 0L
        var neutral = 0L
        for (symbol in text.symbols) {
            runCatching {
                val result = advanced.cluster(symbol, 6, 5)
                if (!result.containsKey("error")) {
                    val clusters = result["clusters"] ?: emptyList<Any>()
                    when (clusters) {
                        is Map<*, *> -> {
                            groups += clusters.size
                            // Source iterates dictionary keys and then fails cluster.get; the increment survives.
                        }
                        is List<*> -> {
                            groups += clusters.size
                            for (item in clusters) {
                                @Suppress("UNCHECKED_CAST")
                                val cluster = item as ReportData
                                val characteristics = cluster.section("characteristics")
                                when {
                                    characteristics.amount("bullish_tendency") > .6 -> bullish += cluster.count("pattern_count")
                                    characteristics.amount("bearish_tendency") >
                                        .6 -> bearish += cluster.count("pattern_count")
                                    else -> neutral += cluster.count("pattern_count")
                                }
                            }
                        }
                    }
                }
            }
        }
        return mapOf(
            "cluster_groups" to
                if (groups ==
                    0
                ) {
                    6
                } else {
                    groups
                },
            "bullish_patterns" to bullish,
            "bearish_patterns" to bearish,
            "neutral_patterns" to neutral,
            "status" to if (groups == 0) "no_data" else "active",
        )
    }

    fun previewBacktesting(): ReportData =
        text.symbols.associateWith { symbol ->
            mapOf(
                "name" to text.names[symbol],
                "best_strategy" to "MA200_breakout_up",
                "avg_return" to 177.2,
                "win_rate" to 0.0,
                "signal_accuracy" to 42.9,
            )
        }

    fun previewPatterns(): ReportData = mapOf("available_patterns" to 0, "message" to "분석 가능한 패턴 데이터 부족")

    fun previewInsights(): ReportData =
        mapOf(
            "nasdaq" to "🟢 나스닥: MA200 근처 지지, 돌파시 강한 상승 예상",
            "sp500" to "🟡 S&P500: 볼린저 밴드 중간선 근처, 방향성 대기",
            "warning" to "🔴 주의사항: RSI 과매수 구간 진입, 단기 조정 가능성",
            "overall_accuracy" to 73.2,
            "analyzed_patterns" to 0,
            "ml_clusters" to 6,
            "risk_level" to "중간",
        )

    fun preview(): ReportData {
        val backtesting = previewBacktesting()
        val patterns = previewPatterns()
        val ml = previewMl()
        val insights = previewInsights()
        val original = text.preview(backtesting, patterns, ml, insights)
        val message = "⚠️ 백테스팅·인사이트는 원본의 고정 시연값이며 실제 성과가 아닙니다.\n\n$original"
        return mapOf(
            "status" to "success",
            "message" to "리포트 미리보기가 생성되었습니다.",
            "preview" to
                mapOf(
                    "report_message" to message,
                    "message_length" to message.codePointCount(0, message.length),
                    "analysis_modules" to
                        mapOf(
                            "backtesting" to backtesting.size,
                            "patterns" to patterns.size,
                            "ml_analysis" to ml.size,
                            "insights" to insights.size,
                        ),
                    "data_provenance" to
                        mapOf(
                            "backtesting" to "source_demo_values",
                            "insights" to "source_demo_values",
                            "ml_analysis" to "stored_patterns_with_source_fallbacks",
                        ),
                ),
        )
    }

    fun summary(sections: ReportData): ReportData {
        val interpretations = sections.section("technical_analysis").section("interpretations")
        val bullish = interpretations.values.count { (it as? Map<*, *>)?.get("signal") in setOf("bullish", "overbought") }
        val bearish = interpretations.values.count { (it as? Map<*, *>)?.get("signal") in setOf("bearish", "oversold") }
        val insights = mutableListOf<String>()
        when (interpretations.section("rsi")["signal"]) {
            "overbought" -> insights += "RSI 지표가 과매수 구간에 있어 단기 조정 가능성"
            "oversold" ->
                insights +=
                    "RSI 지표가 과매도 구간에 있어 반등 가능성"
        }
        val signals = sections.section("signals").section("summary").count("total_signals")
        if (signals > 10) insights += "최근 30일간 ${signals}개의 기술적 신호 발생"
        val news = sections.section("news").count("total_news")
        if (news > 5) insights += "최근 ${news}개의 관련 뉴스 발생"
        return mapOf(
            "overall_sentiment" to
                when {
                    bullish > bearish -> "bullish"
                    bearish > bullish -> "bearish"
                    else -> "neutral"
                },
            "key_insights" to insights,
            "recommendations" to emptyList<Any>(),
            "risk_factors" to emptyList<Any>(),
            "data_quality" to
                sections.filterValues { it is Map<*, *> }.mapValues { (_, value) ->
                    if ((value as Map<*, *>)["status"] ==
                        "success"
                    ) {
                        "good"
                    } else {
                        "poor"
                    }
                },
        )
    }
}
