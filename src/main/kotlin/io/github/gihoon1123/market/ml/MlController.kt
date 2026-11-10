package io.github.gihoon1123.market.ml

import io.github.gihoon1123.market.ApiFailure
import io.github.gihoon1123.market.ApiResponse
import io.github.gihoon1123.market.pythonIso
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class MlTrainRequest(
    @field:NotBlank @field:Size(min = 1, max = 10) val symbol: String,
    @field:Min(100) @field:Max(5000) val training_days: Int = 1000,
    @field:DecimalMin("0.1") @field:DecimalMax("0.4") val validation_split: Double = 0.2,
    val force_retrain: Boolean = false,
    val hyperparameters: Map<String, Any?>? = null,
    val use_sentiment: Boolean = false,
)

data class MlPredictionRequest(
    @field:NotBlank @field:Size(min = 1, max = 10) val symbol: String,
    val prediction_date: LocalDate? = null,
    val timeframes: List<String>? = null,
    val model_version: String? = null,
    val save_results: Boolean = true,
)

@RestController
@RequestMapping("/api")
class MlController(
    private val service: MlPredictionService,
) {
    private fun <T> operation(
        code: String,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (error: ApiFailure) {
            throw error
        } catch (error: Exception) {
            throw ApiFailure(
                500,
                mapOf(
                    "error_code" to code,
                    "message" to (error.message ?: "ML operation failed"),
                    "request_id" to UUID.randomUUID().toString(),
                    "timestamp" to LocalDateTime.now().pythonIso(),
                ),
            )
        }

    @PostMapping("/ml-prediction/train")
    @ResponseStatus(HttpStatus.CREATED)
    fun train(
        @Valid @RequestBody body: MlTrainRequest,
    ): ApiResponse =
        operation("MODEL_TRAINING_FAILED") {
            val request = body.copy(symbol = body.symbol.trim().uppercase())
            ApiResponse(message = "${request.symbol} 모델 훈련이 성공적으로 시작되었습니다", data = service.train(request))
        }

    @PostMapping("/ml-prediction/predict")
    fun predict(
        @Valid @RequestBody body: MlPredictionRequest,
    ): ApiResponse {
        if (body.prediction_date?.isAfter(LocalDate.now()) == true || body.timeframes?.any { it !in setOf("7d", "14d", "30d") } == true) {
            throw ApiFailure(
                422,
                listOf(
                    mapOf(
                        "loc" to
                            listOf(
                                "body",
                                if (body.prediction_date?.isAfter(LocalDate.now()) ==
                                    true
                                ) {
                                    "prediction_date"
                                } else {
                                    "timeframes"
                                },
                            ),
                        "msg" to "Invalid prediction date or timeframe",
                        "type" to "value_error",
                    ),
                ),
            )
        }
        return operation("PREDICTION_FAILED") {
            val request = body.copy(symbol = body.symbol.trim().uppercase())
            ApiResponse(message = "${request.symbol} 가격 예측이 완료되었습니다", data = service.predict(request))
        }
    }

    @GetMapping("/ml-prediction/status")
    fun status(): ApiResponse = operation("INTERNAL_SERVER_ERROR") { ApiResponse(message = "ML 예측 서비스 상태 조회 완료", data = service.status()) }

    @GetMapping("/ml-prediction/health")
    fun health(): ApiResponse =
        ApiResponse(
            message = "ML 예측 서비스가 정상적으로 작동 중입니다",
            data =
                mapOf(
                    "status" to "healthy",
                    "service" to "ml-prediction-api",
                    "timestamp" to LocalDateTime.now().pythonIso(),
                    "version" to "1.0.0",
                    "dependencies" to "installed",
                ),
        )

    @GetMapping("/ml-prediction/info")
    fun info(): ApiResponse =
        ApiResponse(
            message = "ML 예측 시스템 정보 조회 완료",
            data =
                mapOf(
                    "system" to "ML Prediction System",
                    "version" to "1.0.0",
                    "status" to "available",
                    "features" to
                        mapOf(
                            "model_training" to "ready",
                            "price_prediction" to "ready",
                            "model_evaluation" to "training_test_split",
                            "backtesting" to "not_mounted_in_original_router",
                        ),
                    "dependencies" to mapOf("djl" to "0.36.0", "pytorch" to "2.7.1", "runtime" to "JVM CPU"),
                    "endpoints" to
                        listOf(
                            "POST /train - 모델 훈련",
                            "POST /predict - 가격 예측",
                            "GET /status - 서비스 상태",
                            "GET /health - 헬스 체크",
                            "GET /info - 시스템 정보",
                        ),
                    "note" to
                        "Native JVM training and inference; original Keras files require one-time weight/scaler export",
                    "timestamp" to LocalDateTime.now().pythonIso(),
                ),
        )
}
