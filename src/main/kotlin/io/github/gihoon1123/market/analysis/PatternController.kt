package io.github.gihoon1123.market.analysis

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/technical-analysis/patterns")
class PatternController(
    private val service: PatternService,
) {
    @PostMapping("/discover/{symbol}")
    fun discover(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1d") timeframe: String,
    ) = service.discover(symbol, timeframe)

    @GetMapping("/analysis")
    fun analysis(
        @RequestParam(name = "pattern_name", required = false) name: String?,
        @RequestParam(required = false) symbol: String?,
        @RequestParam(name = "min_occurrences", defaultValue = "5") @Min(1) @Max(50) minimum: Int,
    ) = service.analysis(name, symbol, minimum)

    @GetMapping("/successful")
    fun successful(
        @RequestParam(required = false) symbol: String?,
        @RequestParam(name = "success_threshold", defaultValue = "0.7") @DecimalMin("0.1") @DecimalMax("1.0") threshold: Double,
        @RequestParam(name = "min_occurrences", defaultValue = "3") @Min(1) @Max(20) minimum: Int,
    ) = service.successful(symbol, threshold, minimum)

    @PostMapping("/test/{symbol}")
    fun test(
        @PathVariable symbol: String,
    ) = service.test(symbol)

    @DeleteMapping("/test/{symbol}")
    fun cleanup(
        @PathVariable symbol: String,
    ) = service.cleanup(symbol)
}
