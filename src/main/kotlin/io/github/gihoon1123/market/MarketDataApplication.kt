package io.github.gihoon1123.market

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MarketDataApplication

fun main(args: Array<String>) {
    runApplication<MarketDataApplication>(*args)
}
