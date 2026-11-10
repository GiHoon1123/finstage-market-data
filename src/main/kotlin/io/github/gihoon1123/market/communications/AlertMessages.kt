package io.github.gihoon1123.market.communications

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object AlertMessages {
    private fun format(
        value: Double,
        digits: Int = 2,
    ) = String.format(Locale.ROOT, "%.${digits}f", value)

    private fun name(symbol: String): String =
        when (symbol) {
            "^IXIC" -> "나스닥 지수"
            "^GSPC" -> "S&P 500 지수"
            else -> symbol
        }

    fun time(now: LocalDateTime): String =
        now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC (" +
            now.plusHours(9).format(DateTimeFormatter.ofPattern("HH:mm:ss")) +
            " KST)"

    fun ma(
        symbol: String,
        period: Int,
        direction: String,
        now: LocalDateTime,
    ): String {
        val price =
            if (direction == "up") {
                if (symbol == "^IXIC") {
                    20950.5
                } else {
                    6350.75
                }
            } else if (symbol == "^IXIC") {
                20850.5
            } else {
                6250.75
            }
        val ma = if (symbol == "^IXIC") 20900.25 else 6300.5
        val percent = (price - ma) / ma * 100
        val emoji = if (direction == "up") "🚀" else "📉"
        val action = if (direction == "up") "상향 돌파" else "하향 이탈"
        val percentText = (if (direction == "up") "+" else "") + format(percent) + "%"
        return "$emoji <b>${name(
            symbol,
        )}($symbol) ${period}선 $action!</b>\n\n📊 시간대: 일봉\n💵 현재가: ${format(
            price,
        )}\n📈 SMA$period: ${format(ma)}\n📊 돌파폭: <b>$percentText</b>\n🕒 돌파 시점: ${time(now)}"
    }

    fun rsi(
        symbol: String,
        type: String,
        now: LocalDateTime,
    ): String {
        val rsi = if (type == "overbought") 75.8 else 28.3
        val info =
            when (type) {
                "oversold" -> listOf("🟢", "과매도 진입", "RSI 30 이탈 → 반등 가능성", "매수 고려")
                "bullish" -> listOf("📈", "상승 모멘텀", "RSI 50 돌파 → 상승 추세", "상승 추세 확인")
                "bearish" -> listOf("📉", "하락 모멘텀", "RSI 50 이탈 → 하락 추세", "하락 추세 확인")
                else -> listOf("🔴", "과매수 진입", "RSI 70 돌파 → 조정 가능성", "매도 고려")
            }
        return "${info[0]} <b>${name(
            symbol,
        )}($symbol) RSI ${info[1]}!</b>\n\n📊 시간대: 일봉\n📈 현재 RSI: <b>${format(
            rsi,
            1,
        )}</b>\n💡 의미: ${info[2]}\n🎯 전략: ${info[3]}\n🕒 신호 시점: ${time(now)}"
    }

    fun cross(
        symbol: String,
        type: String,
        now: LocalDateTime,
    ): String {
        val upper =
            if (type == "golden") {
                if (symbol == "^IXIC") {
                    20950.5
                } else {
                    6350.75
                }
            } else if (symbol == "^IXIC") {
                20850.5
            } else {
                6250.75
            }
        val lower = if (symbol == "^IXIC") 20900.25 else 6300.5
        val percent = (upper - lower) / lower * 100
        return if (type ==
            "golden"
        ) {
            "🚀 <b>${name(
                symbol,
            )}($symbol) 골든크로스 발생!</b>\n\n📊 시간대: 일봉\n📈 50일선: ${format(
                upper,
            )}\n📈 200일선: ${format(
                lower,
            )}\n📊 차이: <b>+${format(percent)}%</b>\n💡 의미: 강력한 상승 신호! 장기 상승 추세 시작 가능성\n🎯 전략: 매수 포지션 고려\n🕒 발생 시점: ${time(now)}"
        } else {
            "💀 <b>${name(
                symbol,
            )}($symbol) 데드크로스 발생!</b>\n\n📊 시간대: 일봉\n📉 50일선: ${format(
                upper,
            )}\n📈 200일선: ${format(
                lower,
            )}\n📊 차이: <b>${format(percent)}%</b>\n💡 의미: 강력한 하락 신호! 장기 하락 추세 시작 가능성\n🎯 전략: 매도 포지션 고려\n🕒 발생 시점: ${time(now)}"
        }
    }

    fun bollinger(
        symbol: String,
        type: String,
        now: LocalDateTime,
    ): String {
        val upper = if (symbol == "^IXIC") 21000.25 else 6350.5
        val lower = if (symbol == "^IXIC") 20800.25 else 6250.5
        val price =
            if (type in
                listOf("touch_upper", "break_upper")
            ) {
                if (symbol == "^IXIC") {
                    21050.5
                } else {
                    6400.75
                }
            } else if (symbol == "^IXIC") {
                20750.5
            } else {
                6200.75
            }
        val info =
            when (type) {
                "touch_lower" -> listOf("🟢", "하단 밴드 터치", "과매도 신호 → 반등 가능성", "하단 밴드")
                "break_upper" -> listOf("🚀", "상단 밴드 돌파", "강한 상승 신호 → 추가 상승 기대", "상단 밴드")
                "break_lower" -> listOf("💥", "하단 밴드 이탈", "강한 하락 신호 → 추가 하락 우려", "하단 밴드")
                else -> listOf("🔴", "상단 밴드 터치", "과매수 신호 → 조정 가능성", "상단 밴드")
            }
        val band = if (type in listOf("touch_lower", "break_lower")) lower else upper
        return "${info[0]} <b>${name(
            symbol,
        )}($symbol) 볼린저 ${info[1]}!</b>\n\n📊 시간대: 일봉\n💵 현재가: ${format(
            price,
        )}\n📈 ${info[3]}: ${format(band)}\n💡 의미: ${info[2]}\n🕒 신호 시점: ${time(now)}"
    }

    fun header(symbol: String): String =
        "📌 <b>[$symbol] ${SymbolLabels.names[symbol] ?: symbol} (${SymbolLabels.categories[symbol] ?: "기타"})</b>\n\n"
}
