package io.github.gihoon1123.market.jobs

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.ApiResponse
import io.github.gihoon1123.market.pythonIso
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/tasks")
class TaskQueueController(
    private val queue: TaskQueue,
    private val mapper: JsonMapper,
) {
    @GetMapping("/status")
    fun status(): ApiResponse =
        ApiResponse(
            message = "작업 큐 시스템 상태 조회 완료",
            data =
                mapOf(
                    "timestamp" to now(),
                    "system_status" to if (queue.isRunning) "running" else "stopped",
                    "queue_statistics" to queue.stats(),
                ),
        )

    @PostMapping("/submit")
    fun submit(
        @RequestParam(name = "task_name") taskName: String,
        @RequestParam(defaultValue = "normal") priority: String,
        @RequestParam(required = false) args: String?,
        @RequestParam(required = false) kwargs: String?,
    ): ApiResponse {
        val function = BackgroundTasks().aliases[taskName] ?: throw ApiFailure(500, ApiResponse(500, "잘못된 요청: 알 수 없는 작업: $taskName"))
        val parsedArgs: List<Any?>
        val parsedKwargs: Map<String, Any?>
        try {
            parsedArgs = if (args == null) emptyList() else mapper.readValue(args, object : TypeReference<List<Any?>>() {})
            parsedKwargs = if (kwargs == null) emptyMap() else mapper.readValue(kwargs, object : TypeReference<Map<String, Any?>>() {})
        } catch (_: Exception) {
            throw ApiFailure(500, ApiResponse(500, "JSON 파싱 오류"))
        }
        val rank = mapOf("critical" to 0, "high" to 1, "normal" to 2, "low" to 3)[priority.lowercase()] ?: 2
        val id = queue.submit(function, parsedArgs, parsedKwargs, rank)
        return ApiResponse(
            message = "작업이 성공적으로 제출되었습니다",
            data =
                mapOf(
                    "task_id" to id,
                    "task_name" to taskName,
                    "priority" to priority,
                    "submitted_at" to now(),
                ),
        )
    }

    @GetMapping("/task/{task_id}")
    fun task(
        @PathVariable("task_id") id: String,
    ): ApiResponse {
        val result = queue.result(id) ?: throw ApiFailure(404, ApiResponse(404, "작업을 찾을 수 없습니다"))
        val progress =
            mapOf(
                "task_id" to id,
                "status" to result.status.wire,
                "started_at" to result.startedAt?.pythonIso(),
                "completed_at" to result.completedAt?.pythonIso(),
                "execution_time" to result.executionTime,
                "retry_count" to result.retryCount,
                "worker_id" to result.workerId,
                "has_result" to (result.result != null),
                "has_error" to (result.error != null),
            )
        return ApiResponse(message = "작업 $id 상태 조회 완료", data = if (result.error == null) progress else progress + ("error" to result.error))
    }

    @GetMapping("/task/{task_id}/result")
    fun result(
        @PathVariable("task_id") id: String,
    ): Map<String, Any?> {
        val result = queue.result(id) ?: throw ApiFailure(404, "작업을 찾을 수 없습니다")
        if (result.status !in
            listOf(TaskQueue.Status.COMPLETED, TaskQueue.Status.FAILED)
        ) {
            throw ApiFailure(400, "작업이 아직 완료되지 않았습니다. 현재 상태: ${result.status.wire}")
        }
        val response =
            mapOf(
                "task_id" to id,
                "status" to result.status.wire,
                "completed_at" to result.completedAt?.pythonIso(),
                "execution_time" to result.executionTime,
                "worker_id" to result.workerId,
            )
        return if (result.status ==
            TaskQueue.Status.COMPLETED
        ) {
            response + ("result" to result.result)
        } else {
            response + ("error" to result.error)
        }
    }

    @PostMapping("/task/{task_id}/cancel")
    fun cancel(
        @PathVariable("task_id") id: String,
    ): Map<String, Any> =
        if (queue.cancel(id)) {
            mapOf("success" to true, "task_id" to id, "message" to "작업이 성공적으로 취소되었습니다", "cancelled_at" to now())
        } else {
            mapOf("success" to false, "task_id" to id, "message" to "작업을 취소할 수 없습니다 (이미 실행 중이거나 완료됨)")
        }

    @GetMapping("/queue/stats")
    fun stats(): Map<String, Any> =
        queue.stats().let {
            mapOf(
                "timestamp" to now(),
                "queue_stats" to it.getValue("queue_stats"),
                "status_counts" to it.getValue("status_counts"),
                "registered_functions" to it.getValue("registered_functions"),
            )
        }

    @GetMapping("/workers")
    fun workers(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val workers = queue.stats()["worker_stats"] as List<Map<String, Any?>>
        return mapOf(
            "timestamp" to now(),
            "total_workers" to workers.size,
            "active_workers" to workers.count { it["is_running"] == true },
            "workers" to workers,
        )
    }

    @PostMapping("/system/start")
    fun start(): Map<String, Any> =
        if (queue.start()) {
            mapOf("success" to true, "message" to "작업 큐 시스템이 시작되었습니다", "started_at" to now())
        } else {
            mapOf(
                "success" to false,
                "message" to "작업 큐 시스템이 이미 실행 중입니다",
            )
        }

    @PostMapping("/system/stop")
    fun stop(): Map<String, Any> =
        if (runBlocking { queue.stop() }) {
            mapOf(
                "success" to true,
                "message" to "작업 큐 시스템이 중지되었습니다",
                "stopped_at" to now(),
            )
        } else {
            mapOf("success" to false, "message" to "작업 큐 시스템이 실행 중이 아닙니다")
        }

    @PostMapping("/maintenance/cleanup")
    fun cleanup(
        @RequestParam(name = "max_age_hours", defaultValue = "24") @Min(1) @Max(168) hours: Long,
    ): Map<String, Any> {
        queue.cleanup(hours)
        return mapOf("success" to true, "message" to "${hours}시간 이전의 작업 결과가 정리되었습니다", "cleaned_at" to now())
    }

    private fun now(): String = LocalDateTime.now().pythonIso()

    @PostMapping("/quick/dataset-processing")
    fun dataset(
        @RequestParam symbol: String,
        @RequestParam(defaultValue = "1y") period: String,
        @RequestParam(defaultValue = "high") priority: String,
    ): Map<String, Any> {
        val rank = mapOf("critical" to 0, "high" to 1, "normal" to 2, "low" to 3)[priority.lowercase()] ?: 1
        val id = queue.submit(BackgroundTasks().aliases.getValue("process_dataset"), listOf(symbol, period), priority = rank)
        return mapOf(
            "success" to true,
            "task_id" to id,
            "symbol" to symbol,
            "period" to period,
            "priority" to priority,
            "message" to "$symbol 데이터셋 처리 작업이 제출되었습니다",
        )
    }

    @PostMapping("/quick/daily-report")
    fun report(
        @RequestParam symbols: String,
        @RequestParam(required = false) date: String?,
    ): Map<String, Any> {
        val list = symbols.split(",").map(String::trim)
        val id = queue.submit(BackgroundTasks().aliases.getValue("generate_report"), listOf(list, date))
        return mapOf(
            "success" to true,
            "task_id" to id,
            "symbols" to list,
            "date" to (date ?: "today"),
            "message" to "${list.size}개 심볼의 일일 리포트 생성 작업이 제출되었습니다",
        )
    }

    @PostMapping("/quick/market-analysis")
    fun analysis(
        @RequestParam(name = "analysis_type", defaultValue = "comprehensive") type: String,
    ): Map<String, Any> {
        val id = queue.submit(BackgroundTasks().aliases.getValue("market_analysis"), listOf(type))
        return mapOf(
            "success" to true,
            "task_id" to id,
            "analysis_type" to type,
            "message" to "$type 시장 분석 작업이 제출되었습니다",
        )
    }

    @PostMapping("/quick/data-cleanup")
    fun dataCleanup(
        @RequestParam(name = "days_to_keep", defaultValue = "30") @Min(1) @Max(365) days: Int,
    ): Map<String, Any> {
        val id = queue.submit(BackgroundTasks().aliases.getValue("cleanup_data"), listOf(days), priority = 3)
        return mapOf(
            "success" to true,
            "task_id" to id,
            "days_to_keep" to days,
            "message" to "${days}일 이전 데이터 정리 작업이 제출되었습니다",
        )
    }

    @GetMapping("/dashboard")
    fun dashboard(): Map<String, Any> {
        val stats = queue.stats()

        @Suppress("UNCHECKED_CAST")
        val workers = stats.getValue("worker_stats") as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        val queueStats = stats.getValue("queue_stats") as Map<String, Any>
        return mapOf(
            "timestamp" to now(),
            "system_status" to if (queue.isRunning) "running" else "stopped",
            "summary" to
                mapOf(
                    "total_workers" to workers.size,
                    "active_workers" to workers.count { it["is_running"] == true },
                    "pending_tasks" to queueStats.getValue("pending_tasks"),
                    "total_submitted" to queueStats.getValue("total_submitted"),
                    "success_rate" to queueStats.getValue("success_rate"),
                ),
            "status_distribution" to stats.getValue("status_counts"),
            "recent_tasks" to
                queue.recentResults().map {
                    mapOf(
                        "task_id" to it.id,
                        "status" to it.status.wire,
                        "started_at" to it.startedAt?.pythonIso(),
                        "execution_time" to it.executionTime,
                        "worker_id" to it.workerId,
                    )
                },
            "worker_performance" to
                workers.map {
                    mapOf(
                        "worker_id" to it["worker_id"],
                        "processed_tasks" to it["processed_tasks"],
                        "success_rate" to it["success_rate"],
                        "current_task" to it["current_task_id"],
                    )
                },
        )
    }
}
