package io.github.gihoon1123.market.jobs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:jobs;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
    ],
)
@AutoConfigureMockMvc
class TaskQueueHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var queue: TaskQueue

    @Autowired lateinit var mapper: JsonMapper

    @Test
    fun `submitted report runs in background and can be retrieved by same ID`() {
        queue.start()
        val response =
            mvc
                .post("/api/tasks/submit") {
                    param("task_name", "generate_report")
                    param("priority", "high")
                    param("args", """[["AAPL"],"2025-01-01"]""")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.task_name") { value("generate_report") }
                }.andReturn()
        val id =
            mapper
                .readTree(response.response.contentAsString)
                .path("data")
                .path("task_id")
                .asString()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (queue.result(id)?.status !in listOf(TaskQueue.Status.COMPLETED, TaskQueue.Status.FAILED) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
        assertEquals(TaskQueue.Status.COMPLETED, queue.result(id)?.status)
        mvc.get("/api/tasks/task/$id/result").andExpect {
            status { isOk() }
            jsonPath("$.result.report_date") { value("2025-01-01") }
            jsonPath("$.result.summary.total_symbols") { value(1) }
            jsonPath("$.result.symbol_data.AAPL.symbol") { value("AAPL") }
        }
        mvc.get("/api/tasks/task/$id").andExpect {
            status { isOk() }
            jsonPath("$.data.has_result") { value(true) }
            jsonPath("$.data.status") { value("completed") }
        }
    }

    @Test
    fun `queue statistics report actual workers and registered demo handlers`() {
        mvc.get("/api/tasks/status").andExpect {
            status { isOk() }
        }
        mvc.get("/api/tasks/workers").andExpect {
            status { isOk() }
            jsonPath("$.total_workers") { value(4) }
        }
        assertTrue(queue.isRunning)
        assertTrue((queue.stats()["registered_functions"] as List<*>).containsAll(BackgroundTasks().aliases.values))
    }

    @Test
    fun `unknown task and result retain their distinct error envelopes`() {
        mvc.post("/api/tasks/submit?task_name=unknown").andExpect {
            status { isInternalServerError() }
            jsonPath("$.detail.status") { value(500) }
        }
        mvc.get("/api/tasks/task/unknown/result").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("작업을 찾을 수 없습니다") }
        }
    }
}
