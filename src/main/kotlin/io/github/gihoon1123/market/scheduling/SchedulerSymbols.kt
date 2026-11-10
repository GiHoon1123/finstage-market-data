package io.github.gihoon1123.market.scheduling

/** Actual final assignments from the source constants, including the empty ML training map. */
object SchedulerSymbols {
    val indices: List<String> = listOf("^IXIC", "^GSPC")
    val futures: List<String> = listOf("ES=F", "NQ=F")
    val stocks: List<String> = listOf("AAPL", "AMZN", "GOOGL", "TSLA", "MSFT", "META", "NVDA")
    val prices: List<String> = listOf("^IXIC", "^GSPC")
    val mlTraining: List<String> = emptyList()
    val yahooMacro: List<String> =
        listOf(
            "^TNX",
            "^TYX",
            "^IRX",
            "DX-Y.NYB",
            "UUP",
            "UDN",
            "^VIX",
            "^VXN",
            "XLF",
            "XLK",
            "XLE",
            "XLV",
            "XLI",
            "LQD",
            "HYG",
            "JNK",
            "EFA",
            "EEM",
            "IWM",
            "VTI",
        )
    val investingEconomic: List<String> = listOf("INVESTING:ECONOMY", "INVESTING:US")
    val investingMarket: List<String> = listOf("INVESTING:MARKET", "INVESTING:COMMODITY", "INVESTING:FOREX")
    val investingMacro: List<String> =
        listOf(
            "INVESTING:TREASURY",
            "INVESTING:FED",
            "INVESTING:FINANCIAL",
            "INVESTING:TECH",
            "INVESTING:ENERGY",
            "INVESTING:EMERGING",
            "INVESTING:EUROPE",
            "INVESTING:ASIA",
        )
}
