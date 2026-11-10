package io.github.gihoon1123.market.operations

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

data class QuerySample(
    val query: String,
    val duration: Double,
    val affected: Int,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

@Component
class QueryTelemetry : BeanPostProcessor {
    @Volatile var pool: HikariDataSource? = null
    private val samples = ConcurrentLinkedQueue<QuerySample>()
    private val pending = ConcurrentLinkedQueue<QuerySample>()
    private val suppression = ThreadLocal.withInitial { false }
    val threshold = 1.0

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        if (bean !is DataSource) return bean
        if (bean is HikariDataSource) pool = bean
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
            val value = invoke(bean, method, args)
            if (value is Connection && method.name == "getConnection") wrap(value) else value
        }
    }

    fun <T> suppressed(work: () -> T): T {
        val previous = suppression.get()
        suppression.set(true)
        try {
            return work()
        } finally {
            suppression.set(previous)
        }
    }

    private fun wrap(connection: Connection): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val value = invoke(connection, method, args)
            if (value is Statement) {
                val sql = args?.firstOrNull() as? String
                val type =
                    when (value) {
                        is CallableStatement -> CallableStatement::class.java
                        is PreparedStatement -> PreparedStatement::class.java
                        else -> Statement::class.java
                    }
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, statementMethod, statementArgs ->
                    if (statementMethod.name.startsWith("execute") && !suppression.get()) {
                        val query = sql ?: statementArgs?.firstOrNull() as? String ?: "BATCH"
                        val started = System.nanoTime()
                        try {
                            invoke(value, statementMethod, statementArgs)
                        } finally {
                            record(query, (System.nanoTime() - started) / 1e9, runCatching { value.updateCount }.getOrDefault(-1))
                        }
                    } else {
                        invoke(value, statementMethod, statementArgs)
                    }
                }
            } else {
                value
            }
        } as Connection

    fun record(
        query: String,
        duration: Double,
        affected: Int,
    ) {
        val sample = QuerySample(query, duration, affected)
        samples.add(sample)
        while (samples.size > 10000) samples.poll()
        if (duration > threshold) {
            pending.add(sample)
            while (pending.size > 10000) pending.poll()
        }
    }

    fun reset() {
        samples.clear()
        pending.clear()
    }

    fun takePending(): List<QuerySample> {
        val found = mutableListOf<QuerySample>()
        while (true) found.add(pending.poll() ?: break)
        return found
    }

    fun requeue(sample: QuerySample) {
        pending.add(sample)
        while (pending.size > 10000) pending.poll()
    }

    fun statistics(limit: Int): List<Map<String, Any?>> =
        samples
            .groupBy { hash(it.query) }
            .map { (hash, list) ->
                mapOf(
                    "query_hash" to hash,
                    "query_template" to normalize(list.first().query),
                    "execution_count" to list.size,
                    "total_duration" to list.sumOf { it.duration },
                    "avg_duration" to list.map { it.duration }.average(),
                    "min_duration" to list.minOf { it.duration },
                    "max_duration" to list.maxOf { it.duration },
                    "last_execution" to list.maxOf { it.timestamp }.toString(),
                    "slow_query_count" to list.count { it.duration > threshold },
                    "affected_rows" to list.sumOf { it.affected },
                    "table_names" to tables(list.first().query),
                )
            }.sortedByDescending { it["avg_duration"] as Double }
            .take(limit)

    fun summary(): Map<String, Any?> {
        val all = samples.toList()
        val slow = all.count { it.duration > threshold }
        val statistics = statistics(Int.MAX_VALUE)
        val mx = pool?.hikariPoolMXBean
        return mapOf(
            "summary" to
                mapOf(
                    "total_queries" to all.size,
                    "unique_query_patterns" to statistics.size,
                    "slow_queries" to slow,
                    "slow_query_rate" to if (all.isEmpty()) 0.0 else slow * 100.0 / all.size,
                    "avg_query_time" to if (all.isEmpty()) 0.0 else all.map { it.duration }.average(),
                    "active_connections" to mx?.activeConnections,
                    "total_connections" to mx?.totalConnections,
                ),
            "slowest_queries" to
                statistics.sortedByDescending { it["max_duration"] as Double }.take(5).map {
                    it.filterKeys { k ->
                        k in
                            setOf("query_hash", "query_template", "max_duration", "avg_duration", "execution_count")
                    }
                },
            "most_frequent_queries" to
                statistics.sortedByDescending { it["execution_count"] as Int }.take(5).map {
                    it.filterKeys { k ->
                        k in
                            setOf(
                                "query_hash",
                                "query_template",
                                "execution_count",
                                "avg_duration",
                                "total_duration",
                            )
                    }
                },
        )
    }

    companion object {
        fun normalize(query: String): String =
            query
                .trim()
                .uppercase()
                .replace(
                    Regex("'[^']*'"),
                    "?",
                ).replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), "?")
                .replace(Regex("\\s+"), " ")

        fun hash(query: String): String =
            MessageDigest
                .getInstance("MD5")
                .digest(normalize(query).toByteArray())
                .joinToString("") {
                    "%02x".format(it)
                }.take(12)

        fun tables(query: String): List<String> =
            Regex("(?:FROM|JOIN|INTO|UPDATE)\\s+[`\"]?(\\w+)", RegexOption.IGNORE_CASE)
                .findAll(query)
                .map {
                    it.groupValues[1].lowercase()
                }.distinct()
                .toList()

        private fun invoke(
            target: Any,
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(
                    target,
                    *(
                        args
                            ?: emptyArray()
                    ),
                )
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
    }
}
