package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.ApiFailure
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ws")
class WebSocketController(
    private val socket: RealtimeWebSocket,
) {
    @GetMapping("/status")
    fun status() =
        mapOf(
            "timestamp" to System.nanoTime() / 1e9,
            "websocket_manager" to socket.managerStats(),
            "price_streamer" to socket.streamerStats(),
            "system_health" to "healthy",
        )

    @GetMapping("/clients")
    fun clients() = socket.clientStats()

    @PostMapping("/broadcast")
    fun broadcast(
        @RequestParam message: String,
        @RequestParam(name = "message_type", defaultValue = "system_status") messageType: String,
        @RequestParam(name = "target_type", defaultValue = "all") target: String,
    ): Map<String, Any?> {
        if (messageType !in RealtimeWebSocket.messageTypes) throw ApiFailure(400, "잘못된 메시지 타입: $messageType")
        if (target !in RealtimeWebSocket.types) throw ApiFailure(400, "잘못된 대상 타입: $target")
        socket.broadcast(mapOf("message" to message, "source" to "admin", "broadcast_type" to target), messageType, target)
        return mapOf(
            "success" to true,
            "message" to "브로드캐스트 완료",
            "target_type" to target,
            "active_connections" to socket.managerStats()["active_connections"],
        )
    }

    @PostMapping("/streaming/start")
    fun start(
        @RequestParam(required = false) symbols: List<String>?,
    ): Map<String, Any?> {
        if (socket.streaming) return mapOf("success" to false, "message" to "스트리밍이 이미 활성화되어 있습니다", "stats" to socket.streamerStats())
        socket.start(symbols)
        return mapOf(
            "success" to true,
            "message" to "실시간 스트리밍이 시작되었습니다",
            "monitored_symbols" to socket.streamerStats()["monitored_symbols"],
            "stats" to socket.streamerStats(),
        )
    }

    @PostMapping("/streaming/stop")
    fun stop(): Map<String, Any?> {
        if (!socket.streaming) return mapOf("success" to false, "message" to "스트리밍이 활성화되어 있지 않습니다")
        socket.stop()
        return mapOf("success" to true, "message" to "실시간 스트리밍이 중지되었습니다")
    }

    @GetMapping("/demo", produces = ["text/html;charset=utf-8"])
    fun demo(): String = WebSocketDemo.html
}
