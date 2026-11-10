package io.github.gihoon1123.market

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CompanyController(
    private val repository: CompanyRepository,
) {
    @GetMapping("/api/symbols/symbols")
    fun symbols(
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) size: Int,
    ): ApiResponse {
        val total = repository.countSymbols()
        val totalPages = total / size + if (total % size > 0) 1 else 0
        return ApiResponse(
            message = "심볼 목록 조회가 완료되었습니다",
            data =
                mapOf(
                    "items" to repository.symbols((page.toLong() - 1) * size, size),
                    "pagination" to
                        mapOf(
                            "page" to page,
                            "size" to size,
                            "total" to total,
                            "total_pages" to totalPages,
                            "has_next" to (page < totalPages),
                            "has_prev" to (page > 1),
                        ),
                ),
        )
    }

    @GetMapping("/api/financials/financials/{symbol}")
    fun financials(
        @PathVariable @Pattern(regexp = "^[A-Z]{1,10}$") symbol: String,
    ): ApiResponse = ApiResponse(message = "$symbol 재무제표 조회가 완료되었습니다", data = repository.statements(symbol))
}
