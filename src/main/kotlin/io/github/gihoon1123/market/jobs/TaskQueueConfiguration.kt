package io.github.gihoon1123.market.jobs

import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TaskQueueConfiguration {
    @Bean(destroyMethod = "")
    fun taskQueue(): TaskQueue =
        TaskQueue().also {
            BackgroundTasks().register(it)
            it.start()
        }

    @Bean
    fun taskQueueLifecycle(queue: TaskQueue): AutoCloseable = AutoCloseable { runBlocking { queue.stop() } }
}
