package io.github.gihoon1123.market

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class DatabaseConfiguration {
    @Bean
    fun transactionTemplate(manager: PlatformTransactionManager): TransactionTemplate = TransactionTemplate(manager)
}
