package io.github.gihoon1123.market.jobs

import io.github.gihoon1123.market.pythonIso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class TaskQueue(
    private val maxWorkers: Int = 4,
) {
    enum class Status(
        val wire: String,
    ) {
        PENDING("pending"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        RETRYING("retrying"),
    }

    data class Result(
        val id: String,
        val status: Status,
        val result: Any? = null,
        val error: String? = null,
        val startedAt: LocalDateTime? = null,
        val completedAt: LocalDateTime? = null,
        val executionTime: Double? = null,
        val retryCount: Int = 0,
        val workerId: String? = null,
    )

    private data class Task(
        val id: String,
        val function: String,
        val args: List<Any?>,
        val kwargs: Map<String, Any?>,
        val priority: Int,
        val sequence: Long,
        val maxRetries: Int,
        val retryDelayMillis: Long,
        val timeoutMillis: Long?,
    ) : Comparable<Task> {
        override fun compareTo(other: Task): Int = compareValuesBy(this, other, Task::priority, Task::sequence)
    }

    private data class Worker(
        val id: String,
        var currentTask: String? = null,
        var completed: Long = 0,
        var failed: Long = 0,
        val started: LocalDateTime = LocalDateTime.now(),
    )

    @Volatile var isRunning = false
        private set

    @Volatile private var stopping = false

    init {
        require(maxWorkers > 0)
    }

    private val pending = PriorityBlockingQueue<Task>()
    private val results = ConcurrentHashMap<String, Result>()
    private val submissionOrder = ConcurrentLinkedDeque<String>()
    private val registry = ConcurrentHashMap<String, suspend (List<Any?>, Map<String, Any?>) -> Any?>()
    private val workers = ConcurrentHashMap<String, Worker>()
    private val sequence = AtomicLong()
    private val submitted = AtomicLong()
    private val completed = AtomicLong()
    private val failed = AtomicLong()
    private var supervisor = SupervisorJob()
    private var scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private val retries = ConcurrentHashMap<String, Job>()
    private val scheduled = ConcurrentHashMap<String, Task>()

    fun register(
        name: String,
        handler: suspend (List<Any?>, Map<String, Any?>) -> Any?,
    ) {
        registry[name] = handler
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning || stopping) return false
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        isRunning = true
        scheduled.values.forEach { task ->
            results.computeIfPresent(task.id) { _, result -> result.copy(status = Status.PENDING) }
            pending.put(task)
        }
        scheduled.clear()
        repeat(maxWorkers) { index ->
            val worker = Worker("worker_${index + 1}")
            workers[worker.id] = worker
            jobs +=
                scope.launch {
                    while (isRunning) {
                        val next = pending.poll()
                        if (next == null) delay(50) else execute(next, worker)
                    }
                }
        }
        return true
    }

    suspend fun stop(): Boolean {
        val snapshot =
            synchronized(this) {
                if (!isRunning) return false
                isRunning = false
                stopping = true
                supervisor to jobs.toList()
            }
        try {
            withTimeoutOrNull(30000) { snapshot.second.joinAll() }
            snapshot.first.cancelAndJoin()
        } finally {
            synchronized(this) {
                jobs.clear()
                retries.clear()
                stopping = false
            }
        }
        return true
    }

    fun submit(
        function: String,
        args: List<Any?> = emptyList(),
        kwargs: Map<String, Any?> = emptyMap(),
        priority: Int = 2,
        maxRetries: Int = 3,
        retryDelayMillis: Long = 1000,
        timeoutMillis: Long? = null,
    ): String {
        require(priority in 0..3 && maxRetries >= 0 && retryDelayMillis >= 0)
        require(timeoutMillis == null || timeoutMillis > 0)
        val id = UUID.randomUUID().toString()
        val task =
            Task(
                id,
                function,
                args.toList(),
                kwargs.toMap(),
                priority,
                sequence.incrementAndGet(),
                maxRetries,
                retryDelayMillis,
                timeoutMillis,
            )
        results[id] = Result(id, Status.PENDING)
        submissionOrder.add(id)
        submitted.incrementAndGet()
        pending.put(task)
        return id
    }

    fun result(id: String): Result? = results[id]

    fun recentResults(limit: Int = 10): List<Result> = submissionOrder.toList().takeLast(limit).mapNotNull(results::get)

    fun cancel(id: String): Boolean {
        scheduled.remove(id) ?: return false
        retries.remove(id)?.cancel()
        results.computeIfPresent(id) { _, previous -> previous.copy(status = Status.CANCELLED, completedAt = LocalDateTime.now()) }
        return true
    }

    fun cleanup(hours: Long) {
        require(hours > 0)
        val cutoff = LocalDateTime.now().minusHours(hours)
        results.entries.removeIf { it.value.completedAt?.isBefore(cutoff) == true }
        submissionOrder.removeIf { !results.containsKey(it) }
    }

    fun stats(): Map<String, Any> {
        val workerStats =
            workers.values.sortedBy { it.id }.map { worker ->
                mapOf(
                    "worker_id" to worker.id,
                    "is_running" to isRunning,
                    "current_task_id" to worker.currentTask,
                    "processed_tasks" to worker.completed,
                    "failed_tasks" to worker.failed,
                    "success_rate" to
                        if (worker.completed + worker.failed == 0L) 0.0 else worker.completed * 100.0 / (worker.completed + worker.failed),
                    "uptime_seconds" to java.time.Duration
                        .between(worker.started, LocalDateTime.now())
                        .toMillis() / 1000.0,
                    "started_at" to worker.started.pythonIso(),
                )
            }
        return mapOf(
            "timestamp" to LocalDateTime.now().pythonIso(),
            "is_running" to isRunning,
            "queue_stats" to
                mapOf(
                    "pending_tasks" to pending.size,
                    "scheduled_tasks" to scheduled.size,
                    "total_submitted" to submitted.get(),
                    "total_completed" to completed.get(),
                    "total_failed" to failed.get(),
                    "success_rate" to if (submitted.get() == 0L) 0.0 else completed.get() * 100.0 / submitted.get(),
                ),
            "status_counts" to results.values.groupingBy { it.status.wire }.eachCount(),
            "worker_stats" to workerStats,
            "registered_functions" to registry.keys.sorted(),
        )
    }

    private suspend fun execute(
        task: Task,
        worker: Worker,
    ) {
        val prior = results[task.id] ?: return
        if (prior.status == Status.CANCELLED) return
        worker.currentTask = task.id
        val started = LocalDateTime.now()
        val startNanos = System.nanoTime()
        results[task.id] = prior.copy(status = Status.RUNNING, startedAt = started, workerId = worker.id)
        try {
            val handler = registry[task.function] ?: throw IllegalArgumentException("등록되지 않은 함수: ${task.function}")
            val output =
                if (task.timeoutMillis ==
                    null
                ) {
                    handler(task.args, task.kwargs)
                } else {
                    withTimeout(task.timeoutMillis) { handler(task.args, task.kwargs) }
                }
            results[task.id] =
                Result(
                    task.id,
                    Status.COMPLETED,
                    output,
                    startedAt = started,
                    completedAt = LocalDateTime.now(),
                    executionTime =
                        (System.nanoTime() - startNanos) / 1e9,
                    retryCount = prior.retryCount,
                    workerId = worker.id,
                )
            completed.incrementAndGet()
            worker.completed++
        } catch (error: Exception) {
            if (error is CancellationException && !isRunning) {
                results[task.id] = prior.copy(status = Status.PENDING)
                pending.put(task)
                throw error
            }
            if (prior.retryCount < task.maxRetries) {
                results[task.id] =
                    Result(
                        task.id,
                        Status.RETRYING,
                        error = error.message ?: error.javaClass.simpleName,
                        startedAt = started,
                        retryCount =
                            prior.retryCount + 1,
                        workerId = worker.id,
                    )
                scheduled[task.id] = task.copy(sequence = sequence.incrementAndGet())
                val retry =
                    scope.launch(start = CoroutineStart.LAZY) {
                        delay(task.retryDelayMillis * (1L shl minOf(prior.retryCount, 20)))
                        scheduled.remove(task.id)?.let { if (results[task.id]?.status == Status.RETRYING) pending.put(it) }
                        retries.remove(task.id)
                    }
                retries[task.id] = retry
                retry.start()
            } else {
                results[task.id] =
                    Result(
                        task.id,
                        Status.FAILED,
                        error = error.message ?: error.javaClass.simpleName,
                        startedAt = started,
                        completedAt = LocalDateTime.now(),
                        executionTime =
                            (System.nanoTime() - startNanos) / 1e9,
                        retryCount = prior.retryCount,
                        workerId = worker.id,
                    )
                failed.incrementAndGet()
                worker.failed++
            }
        } finally {
            worker.currentTask = null
        }
    }
}
