package io.github.gihoon1123.market.scheduling

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Source interval jobs have max_instances=1; queued and running work both hold ownership. */
class JobSupervisor(
    private val actions: Map<String, () -> Any?>,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private class State {
        val running = AtomicBoolean(false)
        val executions = AtomicLong()
        val successes = AtomicLong()
        val failures = AtomicLong()
        val skipped = AtomicLong()

        @Volatile var started: Instant? = null

        @Volatile var completed: Instant? = null

        @Volatile var error: String? = null
    }

    private val states = actions.keys.associateWith { State() }
    private val timer =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "market-scheduler-timer").apply { isDaemon = true }
        }
    private val workers = Executors.newFixedThreadPool(10) { task -> Thread(task, "market-scheduler-job").apply { isDaemon = true } }
    private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @Volatile private var closed = false

    @Synchronized fun start(intervalMillis: Long = 180000) {
        require(intervalMillis > 0)
        check(!closed) { "Scheduler is closed" }
        if (scheduled.isNotEmpty()) return
        for (name in actions.keys) {
            scheduled[name] =
                timer.scheduleAtFixedRate({ trigger(name) }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        }
    }

    fun trigger(name: String): Boolean {
        val state = requireNotNull(states[name]) { "Unknown scheduled job: $name" }
        if (closed) return false
        if (!state.running.compareAndSet(false, true)) {
            state.skipped.incrementAndGet()
            return false
        }
        return try {
            workers.submit { perform(name, state) }
            true
        } catch (error: RuntimeException) {
            state.running.set(false)
            false
        }
    }

    fun runNow(name: String): Any? {
        val state = requireNotNull(states[name]) { "Unknown scheduled job: $name" }
        check(!closed) { "Scheduler is closed" }
        if (!state.running.compareAndSet(false, true)) {
            state.skipped.incrementAndGet()
            return mapOf(
                "status" to "skipped",
                "reason" to "already_running",
            )
        }
        return perform(name, state)
    }

    private fun perform(
        name: String,
        state: State,
    ): Any? {
        state.started = clock.instant()
        state.executions.incrementAndGet()
        return try {
            val result = actions.getValue(name).invoke()
            state.error = null
            state.successes.incrementAndGet()
            result
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            state.error = error.message ?: error.javaClass.simpleName
            state.failures.incrementAndGet()
            org.slf4j.LoggerFactory
                .getLogger(javaClass)
                .warn("Scheduled job {} failed: {}", name, state.error)
            mapOf("status" to "failed", "error" to state.error)
        } finally {
            state.completed = clock.instant()
            state.running.set(false)
        }
    }

    fun status(): Map<String, Any?> =
        actions.keys.associateWith { name ->
            val state = states.getValue(name)
            mapOf(
                "running" to state.running.get(),
                "scheduled" to scheduled.containsKey(name),
                "executions" to state.executions.get(),
                "successes" to state.successes.get(),
                "failures" to state.failures.get(),
                "skipped" to state.skipped.get(),
                "last_started" to state.started?.toString(),
                "last_completed" to state.completed?.toString(),
                "last_error" to state.error,
            )
        }

    override fun close() {
        closed = true
        scheduled.values.forEach { it.cancel(false) }
        scheduled.clear()
        timer.shutdownNow()
        workers.shutdownNow()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }
}

@Component
class ActiveScheduler(
    tasks: SchedulerTasks,
    @Value("\${scheduler.enabled:false}") val enabled: Boolean,
    @Value("\${scheduler.interval-ms:180000}") private val intervalMillis: Long,
) {
    val supervisor = JobSupervisor(tasks.actions())

    @PostConstruct fun start() {
        if (enabled) supervisor.start(intervalMillis)
    }

    @PreDestroy fun close() {
        supervisor.close()
    }
}
