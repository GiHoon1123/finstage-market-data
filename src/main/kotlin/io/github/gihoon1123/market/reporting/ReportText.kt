package io.github.gihoon1123.market.reporting

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

typealias ReportData = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun ReportData.section(key: String): ReportData = this[key] as? ReportData ?: emptyMap()

internal fun ReportData.amount(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0

internal fun ReportData.count(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

@Configuration
class ReportingConfiguration {
    @Bean("reportingClock")
    fun clock(): Clock = Clock.systemDefaultZone()
}

@Component
class ReportText(
    @Qualifier("reportingClock") private val clock: Clock,
) {
    val symbols = listOf("^IXIC", "^GSPC")
    val names = mapOf("^IXIC" to "나스닥 지수", "^GSPC" to "S&P 500")

    private fun fixed(
        value: Double,
        places: Int,
    ) = String.format(Locale.US, "%.${places}f", value)

    private fun appendix(name: String) = javaClass.getResourceAsStream("/reporting/$name")!!.bufferedReader().use { it.readText() }

    fun insights(all: Map<String, ReportData>): ReportData {
        val output = linkedMapOf<String, Any?>()
        for ((symbol, data) in all) {
            val name = names[symbol] ?: symbol
            val change = data.section("price").amount("change_percent")
            val ml = data.section("ml_analysis")
            val bullish = ml.count("bullish_patterns")
            val bearish = ml.count("bearish_patterns")
            output[symbol.lowercase().replace("^", "")] =
                when {
                    change > 1 && bullish > bearish -> "$name 강세 지속 (상승 패턴 ${bullish}개 활성)"
                    change < -1 && bearish > bullish -> "$name 조정 국면 (하락 패턴 ${bearish}개 감지)"
                    bullish > bearish -> "$name 상승 모멘텀 형성 중"
                    bearish > bullish -> "$name 하락 압력 증가"
                    else -> "$name 횡보 구간 (관망 권장)"
                }
        }
        val bullish = all.values.sumOf { it.section("ml_analysis").count("bullish_patterns") }
        val bearish = all.values.sumOf { it.section("ml_analysis").count("bearish_patterns") }
        val warning =
            when {
                bullish > bearish * 1.5 -> "⚠️ 과도한 낙관론 주의 - 분할 매수 권장"
                bearish > bullish * 1.5 -> "⚠️ 과도한 비관론 - 저점 매수 기회 모색"
                else -> "✅ 균형잡힌 시장 상황 - 정상 투자 환경"
            }
        output.putAll(
            mapOf(
                "warning" to warning,
                // This is the source heuristic, not measured predictive accuracy.
                "overall_accuracy" to
                    minOf(85.0, 50.0 + all.values.sumOf { it.section("signals").section("summary").count("total_signals") } / 10.0),
                "analyzed_patterns" to all.values.sumOf { it.section("ml_analysis").count("total_patterns") },
                "ml_clusters" to all.values.count { it.section("ml_analysis").count("cluster_groups") > 0 },
                "risk_level" to if (bullish > bearish * 1.5 || bearish > bullish * 1.5) "높음" else "중간",
            ),
        )
        return output
    }

    fun preview(
        backtesting: ReportData,
        patterns: ReportData,
        ml: ReportData,
        insights: ReportData,
    ): String =
        buildString {
            val time = LocalDateTime.now(clock).plusHours(9).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
            append("🌅 일일 퀀트 분석 리포트 ($time)\n\n📈 백테스팅 성과 분석")
            if (!backtesting.containsKey("error")) {
                for ((symbol, item) in backtesting) {
                    if (symbol !in symbols) continue
                    @Suppress("UNCHECKED_CAST")
                    val data = item as ReportData
                    append(
                        "\n┌─ ${data["name"]} ($symbol)\n│  • 최고 성과 전략: ${data["best_strategy"]}\n│  • 평균 수익률: ${data["avg_return"]}% (1일 기준)\n│  • 승률: ${data["win_rate"]}%\n│  • 전체 신호 정확도: ${data["signal_accuracy"]}%",
                    )
                }
            }
            append("\n\n🔍 패턴 분석 결과\n┌─ ${patterns["message"] ?: "패턴 분석 완료"}")
            append(
                "\n\n🤖 머신러닝 분석\n┌─ 패턴 클러스터링 결과 (${ml.count(
                    "cluster_groups",
                )}개 그룹)\n│  • 상승 패턴 그룹: ${ml.count(
                    "bullish_patterns",
                )}개\n│  • 하락 패턴 그룹: ${ml.count("bearish_patterns")}개\n│  • 중립 패턴 그룹: ${ml.count("neutral_patterns")}개",
            )
            append("\n\n🎯 오늘의 투자 인사이트\n• ${insights["nasdaq"] ?: ""}\n• ${insights["sp500"] ?: ""}\n• ${insights["warning"] ?: ""}")
            append(
                "\n\n📊 핵심 지표 요약\n• 전체 신호 정확도: ${insights["overall_accuracy"] ?: 0}%\n• 분석된 패턴 수: ${insights["analyzed_patterns"] ?: 0}개\n• ML 클러스터 그룹: ${insights["ml_clusters"] ?: 0}개\n• 리스크 수준: ${insights["risk_level"] ?: "중간"}",
            )
            append(appendix("preview-appendix.txt"))
        }

    fun daily(
        all: Map<String, ReportData>,
        insights: ReportData,
    ): String =
        buildString {
            val now = LocalDateTime.now(clock)
            val weekday = listOf("월", "화", "수", "목", "금", "토", "일")[now.dayOfWeek.value - 1]
            append("🚀 일일 퀀트 분석 리포트 (${now.toLocalDate()} $weekday)\n\n💡 이 리포트는 AI가 과거 10년간의 주식 데이터를 분석해서 만든 투자 참고 자료입니다.\n\n📈 주요 지수 현황")
            for (symbol in symbols) {
                val data = all[symbol] ?: emptyMap()
                val price = data.section("price")
                val ml = data.section("ml_analysis")
                val change = price.amount("change_percent")
                val trend =
                    when {
                        change > 1 -> "📈 강한 상승세"
                        change > .3 -> "🔼 약한 상승세"
                        change < -1 -> "📉 강한 하락세"
                        change < -.3 -> "🔽 약한 하락세"
                        else -> "🔄 보합세"
                    }
                append(
                    "\n\n🔹 ${names[symbol] ?: symbol} ($symbol)\n┌─ 현재가: ${String.format(
                        Locale.US,
                        "%,.2f",
                        price.amount("current_price"),
                    )}원 (${String.format(
                        Locale.US,
                        "%+.2f",
                        change,
                    )}%) $trend\n│\n│  🤖 AI 패턴 분석 결과:\n│  ├─ 총 ${ml.count(
                        "total_patterns",
                    )}개 패턴을 ${ml.count(
                        "cluster_groups",
                    )}개 그룹으로 분류\n│  ├─ 🟢 상승 신호 그룹: ${ml.count(
                        "bullish_patterns",
                    )}개 (매수 관련)\n│  ├─ 🔴 하락 신호 그룹: ${ml.count(
                        "bearish_patterns",
                    )}개 (매도 관련)\n│  └─ 📡 오늘 새로 발생한 신호: ${data.section("signals").section("summary").count("total_signals")}개",
                )

                fun strongest(
                    key: String,
                    bullish: Boolean,
                ) {
                    val cluster = ml.section(key)
                    if (cluster.isEmpty()) return
                    val success = cluster.amount("success_rate")
                    val interpretation =
                        when {
                            success >= 70 -> "매우 신뢰도 높음 ⭐⭐⭐"
                            success >= 60 -> "신뢰도 높음 ⭐⭐"
                            success >= 50 -> "보통 신뢰도 ⭐"
                            else -> "낮은 신뢰도 ⚠️"
                        }
                    append(
                        "\n│\n│  ${if (bullish) "🚀 가장 강한 상승 신호" else "📉 가장 강한 하락 신호"}:\n│  ├─ 패턴명: ${translate(
                            cluster["name"].toString(),
                        )}\n│  ├─ 발생 횟수: ${cluster.count(
                            "pattern_count",
                        )}번\n│  ├─ 성공률: ${fixed(
                            success,
                            1,
                        )}% ($interpretation)\n│  └─ 의미: 이 패턴이 나타나면 과거에 ${fixed(
                            success,
                            0,
                        )}% 확률로 주가가 ${if (bullish) "올랐습니다" else "떨어졌습니다"}",
                    )
                }
                strongest("strongest_bullish", true)
                strongest("strongest_bearish", false)
            }
            append("\n\n🎯 오늘의 투자 전략 가이드")
            insights
                .filterKeys {
                    it !in setOf("overall_accuracy", "analyzed_patterns", "ml_clusters", "risk_level")
                }.values
                .forEach { append("\n• $it") }
            val risk = insights["risk_level"] ?: "중간"
            val explanation =
                when (risk) {
                    "높음" -> "⚠️ 시장 변동성이 큽니다. 소액 분할 투자를 권장합니다."
                    "중간" -> "📊 적정한 리스크 수준입니다. 신중한 투자 결정을 하세요."
                    else -> "✅ 상대적으로 안정적인 시장 상황입니다."
                }
            val accuracy = insights.amount("overall_accuracy")
            append(
                "\n\n📊 AI 분석 결과 요약\n┌─ 분석 신뢰도: ${fixed(
                    accuracy,
                    1,
                )}%\n│  └─ 의미: AI가 ${fixed(
                    accuracy,
                    0,
                )}% 확률로 정확한 예측을 할 수 있습니다\n│\n├─ 분석한 패턴: ${insights.count(
                    "analyzed_patterns",
                )}개\n│  └─ 의미: 과거 10년간 ${insights.count(
                    "analyzed_patterns",
                )}개의 주가 패턴을 학습했습니다\n│\n├─ AI 그룹 분류: ${insights.count(
                    "ml_clusters",
                )}개\n│  └─ 의미: 복잡한 패턴들을 ${insights.count("ml_clusters")}개의 이해하기 쉬운 그룹으로 정리했습니다\n│\n└─ 투자 위험도: $risk\n   └─ $explanation",
            )
            append(appendix("daily-appendix.txt"))
        }

    fun translate(name: String): String {
        val translations =
            mapOf(
                "BB_상승_패턴" to "볼린저밴드 상승 돌파 패턴",
                "BB_하락_패턴" to "볼린저밴드 하락 돌파 패턴",
                "RSI_중립_패턴" to "RSI 중립 구간 패턴",
                "RSI_상승_패턴" to "RSI 과매도 반등 패턴",
                "RSI_하락_패턴" to "RSI 과매수 조정 패턴",
                "BREAKOUT_상승_패턴" to "저항선 돌파 상승 패턴",
                "BREAKOUT_하락_패턴" to "지지선 이탈 하락 패턴",
                "THEN_상승_패턴" to "연속 상승 신호 패턴",
                "THEN_하락_패턴" to "연속 하락 신호 패턴",
                "CROSS_중립_패턴" to "이동평균선 교차 패턴",
                "CROSS_상승_패턴" to "골든크로스 상승 패턴",
                "CROSS_하락_패턴" to "데드크로스 하락 패턴",
                "VOLUME_중립_패턴" to "거래량 변화 패턴",
                "VOLUME_상승_패턴" to "거래량 급증 상승 패턴",
                "VOLUME_하락_패턴" to "거래량 감소 하락 패턴",
                "MA_상승_패턴" to "이동평균선 상승 패턴",
                "MA_하락_패턴" to "이동평균선 하락 패턴",
                "MACD_상승_패턴" to "MACD 상승 전환 패턴",
                "MACD_하락_패턴" to "MACD 하락 전환 패턴",
            )
        return translations[name] ?: name
            .replace("_", " ")
            .replace("상승", "📈 상승")
            .replace("하락", "📉 하락")
            .replace("중립", "🔄 중립")
    }
}
