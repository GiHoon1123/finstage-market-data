package io.github.gihoon1123.market.operations

import io.github.gihoon1123.market.MarketDataClient
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@Component
class RealtimeWebSocket(
    private val mapper: JsonMapper,
    private val market: MarketDataClient,
) : TextWebSocketHandler() {
    data class Client(
        val id: String,
        val session: WebSocketSession,
        val connected: LocalDateTime = LocalDateTime.now(),
        var heartbeat: Double = RuntimeMeasurements.epoch(),
        val symbols: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val types: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var sent: Long = 0,
        var errors: Long = 0,
    )

    private val clients = ConcurrentHashMap<String, Client>()
    private val totalConnections = AtomicLong()
    private val sent = AtomicLong()
    private val errors = AtomicLong()
    private val prices = ConcurrentHashMap<String, Double>()
    private val history = ConcurrentHashMap<String, CopyOnWriteArrayList<Map<String, Any?>>>()
    private val symbols: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val executor = Executors.newScheduledThreadPool(2) { task -> Thread(task, "price-stream").apply { isDaemon = true } }

    @Volatile final var streaming = false
        private set

    @Volatile private var lastUpdate: String? = null
    private val updates = AtomicLong()
    private val alerts = AtomicLong()

    init {
        executor.scheduleWithFixedDelay({
            if (clients.isNotEmpty()) broadcast(mapOf("server_time" to RuntimeMeasurements.epoch()), "heartbeat", "all")
        }, 30, 30, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({
            clients.values.filter { RuntimeMeasurements.epoch() - it.heartbeat > 120 }.forEach {
                runCatching { it.session.close(CloseStatus.SESSION_NOT_RELIABLE) }
                clients.remove(it.id)
            }
        }, 60, 60, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({ if (streaming) runCatching { streamCycle() } }, 1, 300, TimeUnit.SECONDS)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val id = UUID.randomUUID().toString()
        session.attributes["client_id"] = id
        val client = Client(id, session)
        clients[id] = client
        totalConnections.incrementAndGet()
        send(
            client,
            mapOf(
                "type" to "system_status",
                "message" to "WebSocket 연결이 성공했습니다",
                "client_id" to id,
                "available_actions" to listOf("subscribe", "unsubscribe", "heartbeat", "get_status"),
                "subscription_types" to types,
            ),
        )
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        session.attributes["client_id"]?.toString()?.let { clients.remove(it) }
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val client = clients[session.attributes["client_id"]?.toString()] ?: return
        val data =
            try {
                mapper.readTree(message.payload)
            } catch (_: Exception) {
                send(
                    client,
                    error("잘못된 JSON 형식입니다") + ("timestamp" to System.nanoTime() / 1e9),
                )
                return
            }
        if (!data.isObject) {
            send(client, error("메시지는 JSON 객체여야 합니다"))
            return
        }
        val map = data.properties().associate { it.key to it.value }
        val symbols: List<String> =
            map["symbols"]
                ?.takeIf { it.isArray }
                ?.toList()
                ?.map { it.asText() }
                .orEmpty()
        val action = map["action"]?.asText()
        val type = map["type"]?.asText()
        val response: Map<String, Any?> =
            when (action) {
                null, "" -> error("action 필드가 필요합니다")
                "subscribe", "unsubscribe" -> {
                    if (type != null &&
                        type !in types
                    ) {
                        error("잘못된 구독 타입: $type")
                    } else if (action == "subscribe" &&
                        type == null
                    ) {
                        error("구독 타입이 필요합니다 (prices, technical_analysis, alerts, all)")
                    } else {
                        if (action == "subscribe") {
                            client.types.add(type!!)
                            client.symbols.addAll(symbols)
                            if (type in listOf("prices", "all") && !streaming) start(null)
                        } else {
                            if (type != null) client.types.remove(type)
                            client.symbols.removeAll(symbols.toSet())
                        }
                        mapOf(
                            "type" to "subscription",
                            "action" to
                                if (action ==
                                    "subscribe"
                                ) {
                                    "subscribed"
                                } else {
                                    "unsubscribed"
                                },
                            "subscription_type" to type,
                            "symbols" to symbols,
                            "message" to if (action == "subscribe") "$type 구독이 완료되었습니다" else "구독 해제가 완료되었습니다",
                        )
                    }
                }
                "heartbeat" -> {
                    client.heartbeat = RuntimeMeasurements.epoch()
                    mapOf("type" to "heartbeat", "message" to "pong", "server_time" to System.nanoTime() / 1e9)
                }
                "get_status" ->
                    mapOf(
                        "type" to "system_status",
                        "client_stats" to clientStats(client),
                        "websocket_manager_stats" to managerStats(),
                        "price_streamer_stats" to streamerStats(),
                    )
                "get_current_prices" ->
                    mapOf(
                        "type" to "price_update",
                        "action" to "current_prices",
                        "prices" to if (symbols.isEmpty()) prices.toMap() else symbols.associateWith { prices[it] },
                        "timestamp" to System.nanoTime() / 1e9,
                    )
                "get_price_history" -> {
                    val symbol = map["symbol"]?.asText()
                    if (symbol.isNullOrEmpty()) {
                        error("symbol 필드가 필요합니다")
                    } else {
                        val limit = map["limit"]?.asInt() ?: 50
                        val rows = history(symbol, limit)
                        mapOf(
                            "type" to "price_update",
                            "action" to "price_history",
                            "symbol" to symbol,
                            "history" to rows,
                            "count" to rows.size,
                        )
                    }
                }
                else -> error("알 수 없는 액션: $action")
            }
        send(client, response)
    }

    private fun send(
        client: Client,
        message: Map<String, Any?>,
    ) {
        try {
            synchronized(client.session) {
                if (client.session.isOpen) {
                    client.session.sendMessage(TextMessage(mapper.writeValueAsString(message)))
                    client.sent++
                    sent.incrementAndGet()
                }
            }
        } catch (_: Exception) {
            client.errors++
            errors.incrementAndGet()
            clients.remove(client.id)
            runCatching { client.session.close() }
        }
    }

    fun broadcast(
        message: Map<String, Any?>,
        messageType: String,
        target: String,
        symbol: String? = null,
    ) {
        val enhanced = message + mapOf("type" to messageType, "timestamp" to RuntimeMeasurements.now(), "symbol" to symbol)
        clients.values
            .filter {
                target == "all" || "all" in it.types || (target in it.types && (symbol == null || symbol in it.symbols))
            }.forEach { send(it, enhanced) }
    }

    fun managerStats(): Map<String, Any?> =
        mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "active_connections" to clients.size,
            "total_connections" to totalConnections.get(),
            "total_messages_sent" to sent.get(),
            "total_errors" to errors.get(),
            "subscribed_symbols" to
                clients.values
                    .flatMap { it.symbols }
                    .toSet()
                    .size,
            "subscription_stats" to types.associateWith { type -> clients.values.count { type in it.types } },
            "background_tasks_active" to mapOf("heartbeat" to clients.isNotEmpty(), "cleanup" to clients.isNotEmpty()),
        )

    fun clientStats(client: Client): Map<String, Any?> =
        mapOf(
            "client_id" to client.id,
            "connected_at" to client.connected.toString(),
            "connection_duration_seconds" to java.time.Duration
                .between(client.connected, LocalDateTime.now())
                .toMillis() / 1000.0,
            "last_heartbeat" to client.heartbeat,
            "subscriptions" to client.symbols.toList(),
            "subscription_types" to client.types.toList(),
            "message_count" to client.sent,
            "error_count" to client.errors,
            "is_active" to client.session.isOpen,
        )

    fun clientStats(): Map<String, Any?> = mapOf("clients" to clients.values.map { clientStats(it) })

    fun start(requested: List<String>?) {
        if (streaming) return
        symbols.clear()
        symbols.addAll(requested?.takeIf { it.isNotEmpty() } ?: listOf("^IXIC", "^GSPC"))
        streaming = true
        executor.execute { if (streaming) runCatching { streamCycle() } }
    }

    fun stop() {
        streaming = false
    }

    fun streamerStats(): Map<String, Any?> =
        mapOf(
            "timestamp" to RuntimeMeasurements.now(),
            "is_streaming" to streaming,
            "monitored_symbols" to symbols.size,
            "symbols_with_prices" to prices.size,
            "total_updates_sent" to updates.get(),
            "total_alerts_sent" to alerts.get(),
            "last_update_time" to lastUpdate,
            "update_interval_seconds" to 300,
            "price_history_size" to history.values.sumOf { it.size },
        )

    fun history(
        symbol: String,
        limit: Int,
    ): List<Map<String, Any?>> {
        val rows = history[symbol]?.toList().orEmpty()
        return if (limit == 0) {
            rows
        } else if (limit > 0) {
            rows.takeLast(limit)
        } else {
            rows.drop(-limit)
        }
    }

    @Synchronized fun streamCycle() {
        for (symbol in symbols.toList()) {
            if (!streaming) break
            val price = market.current(symbol) ?: continue
            val previous = prices.put(symbol, price)
            if (previous != null && abs(price - previous) <= .001) continue
            val change = previous?.let { price - it }
            val pct = previous?.takeIf { it != 0.0 }?.let { change!! / it * 100 }
            val time = RuntimeMeasurements.now()
            val hour = LocalDateTime.now().hour
            val status =
                when {
                    hour in 9..16 -> "open"
                    hour in 4..9 -> "pre_market"
                    hour in 16..20 -> "after_market"
                    else -> "closed"
                }
            val message =
                mapOf(
                    "symbol" to symbol,
                    "current_price" to price,
                    "previous_price" to previous,
                    "change_amount" to change,
                    "change_percent" to pct,
                    "volume" to null,
                    "market_status" to status,
                    "timestamp" to time,
                )
            val list = history.computeIfAbsent(symbol) { CopyOnWriteArrayList() }
            list.add(mapOf("symbol" to symbol, "price" to price, "change_amount" to change, "change_percent" to pct, "timestamp" to time))
            while (list.size > 100) list.removeAt(0)
            broadcast(message, "price_update", "prices", symbol)
            updates.incrementAndGet()
            if (pct != null && symbol !in setOf("^IXIC", "^GSPC") && abs(pct) >= 3.0) {
                val rising = pct > 0
                val formatted = "%.1f".format(java.util.Locale.ROOT, abs(pct))
                val amount = "%.2f".format(java.util.Locale.ROOT, price)
                val changeMessage = "$symbol $formatted% ${if (rising) "상승" else "하락"} (\$$amount)"
                broadcast(
                    mapOf(
                        "symbol" to symbol,
                        "alert_type" to if (rising) "price_rise" else "price_drop",
                        "message" to changeMessage,
                        "current_price" to price,
                        "threshold_value" to 3.0,
                        "severity" to if (rising) "info" else "warning",
                    ),
                    "alert",
                    "alerts",
                )
                alerts.incrementAndGet()
            }
            if (pct != null &&
                abs(pct) >= 5
            ) {
                val alert =
                    mapOf(
                        "symbol" to symbol,
                        "alert_type" to
                            if (pct >
                                0
                            ) {
                                "surge"
                            } else {
                                "plunge"
                            },
                        "message" to "$symbol 급${if (pct > 0) "등" else "락"} ${"%.1f".format(java.util.Locale.ROOT,abs(pct))}%",
                        "current_price" to price,
                        "threshold_value" to 5.0,
                        "severity" to if (abs(pct) >= 10) "high" else "medium",
                    )
                broadcast(alert, "alert", "alerts")
                alerts.incrementAndGet()
            }
            // The source disables category thresholds for the two monitored indexes; custom symbols use +/-3%.
        }
        lastUpdate = RuntimeMeasurements.now()
    }

    @PreDestroy fun close() {
        streaming = false
        executor.shutdownNow()
        clients.values.forEach { runCatching { it.session.close() } }
        clients.clear()
    }

    companion object {
        val types = listOf("prices", "technical_analysis", "alerts", "all")
        val messageTypes = listOf("price_update", "technical_analysis", "alert", "system_status", "subscription", "heartbeat", "error")

        fun error(message: String): Map<String, Any?> = mapOf("type" to "error", "message" to message)
    }
}

@Configuration
@EnableWebSocket
class RealtimeConfiguration(
    private val handler: RealtimeWebSocket,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/realtime").setAllowedOriginPatterns("*")
    }
}
