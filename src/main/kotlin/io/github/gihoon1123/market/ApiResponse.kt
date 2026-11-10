package io.github.gihoon1123.market

data class ApiResponse(
    val status: Int = 200,
    val message: String = "성공적으로 처리되었습니다",
    val data: Any? = null,
)
