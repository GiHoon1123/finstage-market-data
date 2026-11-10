package io.github.gihoon1123.market.communications

import io.github.gihoon1123.market.ApiResponse
import io.github.gihoon1123.market.pythonIso
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/test")
class AlertTestController(
    private val telegram: TelegramAdapter,
    private val jdbc: JdbcTemplate,
) {
    private fun now() = LocalDateTime.now(ZoneOffset.UTC)

    private fun send(
        symbol: String,
        message: String,
    ) = telegram.send(AlertMessages.header(symbol) + message)

    @PostMapping("/telegram-alerts/all")
    fun all(): ApiResponse {
        val time = now()
        val symbol = "^IXIC"
        send(symbol, AlertMessages.ma(symbol, 200, "up", time))
        send(symbol, AlertMessages.rsi(symbol, "overbought", time))
        send(symbol, AlertMessages.cross(symbol, "golden", time))
        send(symbol, AlertMessages.bollinger(symbol, "touch_upper", time))
        return ApiResponse(
            message = "모든 텔레그램 알림 테스트가 성공적으로 실행되었습니다",
            data =
                mapOf(
                    "alert_type" to "ALL_ALERTS",
                    "timestamp" to time.pythonIso(),
                ),
        )
    }

    @PostMapping("/telegram-alerts/ma-breakout")
    fun ma(
        @RequestParam(defaultValue = "AAPL") symbol: String,
        @RequestParam(name = "ma_period", defaultValue = "200") period: Int,
        @RequestParam(name = "ma_type", defaultValue = "SMA") maType: String,
        @RequestParam(defaultValue = "up") direction: String,
    ): Map<String, Any?> {
        val time = now()
        send(symbol, AlertMessages.ma(symbol, period, direction, time))
        return mapOf(
            "success" to true,
            "message" to "$symbol ${period}일선 $direction 돌파 알림 테스트가 성공적으로 실행되었습니다",
            "symbol" to symbol,
            "alert_type" to null,
            "test_data" to mapOf("ma_period" to period, "direction" to direction),
            "timestamp" to time.pythonIso(),
        )
    }

    @PostMapping("/telegram-alerts/rsi")
    fun rsi(
        @RequestParam(defaultValue = "AAPL") symbol: String,
        @RequestParam(name = "signal_type", defaultValue = "overbought") type: String,
    ): Map<String, Any?> {
        val time = now()
        send(symbol, AlertMessages.rsi(symbol, type, time))
        return mapOf(
            "success" to true,
            "message" to "$symbol RSI $type 알림 테스트가 성공적으로 실행되었습니다",
            "symbol" to symbol,
            "alert_type" to null,
            "test_data" to
                mapOf(
                    "signal_type" to type,
                    "current_rsi" to
                        if (type ==
                            "overbought"
                        ) {
                            75.8
                        } else {
                            28.3
                        },
                ),
            "timestamp" to time.pythonIso(),
        )
    }

    @PostMapping("/telegram-alerts/cross")
    fun cross(
        @RequestParam(defaultValue = "AAPL") symbol: String,
        @RequestParam(name = "cross_type", defaultValue = "golden") type: String,
    ): Map<String, Any?> {
        val time = now()
        send(symbol, AlertMessages.cross(symbol, type, time))
        return result("$symbol $type 크로스 알림 테스트가 성공적으로 실행되었습니다.", time)
    }

    @PostMapping("/telegram-alerts/bollinger")
    fun bollinger(
        @RequestParam(defaultValue = "AAPL") symbol: String,
        @RequestParam(name = "signal_type", defaultValue = "touch_upper") type: String,
    ): Map<String, Any?> {
        val time = now()
        send(symbol, AlertMessages.bollinger(symbol, type, time))
        return result("$symbol 볼린저 밴드 $type 알림 테스트가 성공적으로 실행되었습니다.", time)
    }

    @GetMapping("/env-check")
    fun environment(): Map<String, Any?> =
        mapOf(
            "telegram_configured" to telegram.configured(),
            "database_connected" to runCatching { jdbc.queryForObject("SELECT 1", Int::class.java) == 1 }.getOrDefault(false),
            "api_keys_valid" to telegram.configured(),
            "environment" to (System.getenv("ENV_MODE") ?: "알 수 없음"),
            "config_details" to
                mapOf(
                    "telegram_delivery" to if (telegram.configured()) "configured" else "disabled",
                    "credential_validation" to "configuration presence only; no external request",
                ),
            "timestamp" to now().pythonIso(),
        )

    private fun result(
        message: String,
        time: LocalDateTime,
    ): Map<String, Any?> =
        mapOf(
            "success" to true,
            "message" to message,
            "symbol" to null,
            "alert_type" to null,
            "test_data" to null,
            "timestamp" to time.pythonIso(),
        )
}
