package io.github.gihoon1123.market.reporting

interface DailyReports {
    fun generateDailyReport(): Map<String, Any?>

    fun comprehensive(symbol: String): Map<String, Any?>
}
