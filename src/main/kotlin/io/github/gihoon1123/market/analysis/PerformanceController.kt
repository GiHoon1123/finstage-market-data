package io.github.gihoon1123.market.analysis

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/technical-analysis/signals")
class PerformanceController(
    private val service: PerformanceService,
) {
    @GetMapping("/performance/all")
    fun all(
        @RequestParam(name = "timeframe_eval", defaultValue = "1d") horizon: String,
        @RequestParam(name = "min_samples", defaultValue = "10") @Min(1) @Max(100) minimum: Int,
    ) = service.all(horizon, minimum)

    @GetMapping("/performance/{signal_type}")
    fun specific(
        @PathVariable("signal_type") type: String,
        @RequestParam(required = false) symbol: String?,
    ) = analysisJson(service.specific(type, symbol))

    @GetMapping("/quality/{signal_type}")
    fun quality(
        @PathVariable("signal_type") type: String,
        @RequestParam(required = false) symbol: String?,
    ) = analysisJson(service.quality(type, symbol))

    @PostMapping("/backtest/strategy")
    fun strategy(
        @RequestParam(name = "signal_types") types: List<String>,
        @RequestParam(name = "initial_capital", defaultValue = "10000.0") @DecimalMin("1000") capital: Double,
        @RequestParam(name = "position_size", defaultValue = "0.1") @DecimalMin("0.01") @DecimalMax("1.0") position: Double,
        @RequestParam(name = "stop_loss", required = false) stop: Double?,
        @RequestParam(name = "take_profit", required = false) profit: Double?,
        @RequestParam(defaultValue = "90") @Min(7) @Max(365) days: Int,
    ) = analysisJson(service.simulate(types, capital, position, stop, profit, days))
}
