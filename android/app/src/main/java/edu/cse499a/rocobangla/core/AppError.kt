package edu.cse499a.rocobangla.core

import java.io.IOException

/** Every failure the user can see. UI text for each lives in strings.xml. */
enum class AppError {
    // Server error codes (API.md)
    InvalidImage,
    TooLarge,
    ModelNotReady,
    QueueFull,
    EmptyOutput,
    InferenceFailed,
    Unauthorized,
    JobNotFound,

    // Client-side conditions
    Offline,
    Timeout,
    NotConfigured,
    UnsupportedImageType,
    ImageUnreadable,
    ServerError,
}

/** The next step offered to the user for an error. */
enum class ErrorAction { Retry, OpenSettings, PickAnotherImage }

object ErrorMapping {

    fun fromServerCode(code: String?, httpStatus: Int?): AppError = when (code) {
        "INVALID_IMAGE" -> AppError.InvalidImage
        "TOO_LARGE" -> AppError.TooLarge
        "MODEL_NOT_READY" -> AppError.ModelNotReady
        "QUEUE_FULL" -> AppError.QueueFull
        "EMPTY_OUTPUT" -> AppError.EmptyOutput
        "INFERENCE_FAILED" -> AppError.InferenceFailed
        "UNAUTHORIZED" -> AppError.Unauthorized
        "NOT_FOUND" -> AppError.JobNotFound
        else -> fromHttpStatus(httpStatus)
    }

    /** A status without one of our JSON error bodies came from something in
     *  between (usually the tunnel when Colab has stopped). */
    private fun fromHttpStatus(status: Int?): AppError = when (status) {
        null -> AppError.ServerError
        401 -> AppError.Unauthorized
        413 -> AppError.TooLarge
        429 -> AppError.QueueFull
        404, 502, 503, 504, in 520..530 -> AppError.Offline
        else -> AppError.ServerError
    }

    fun fromThrowable(t: Throwable): AppError = when (t) {
        is IOException -> AppError.Offline // DNS, TLS, refused, reset, socket timeout
        else -> AppError.ServerError // e.g. a body that is not our JSON
    }

    fun actionsFor(error: AppError): List<ErrorAction> = when (error) {
        AppError.InvalidImage,
        AppError.TooLarge,
        AppError.UnsupportedImageType,
        AppError.ImageUnreadable -> listOf(ErrorAction.PickAnotherImage)

        AppError.Unauthorized,
        AppError.NotConfigured -> listOf(ErrorAction.OpenSettings)

        AppError.Offline,
        AppError.JobNotFound -> listOf(ErrorAction.Retry, ErrorAction.OpenSettings)

        AppError.ModelNotReady,
        AppError.QueueFull,
        AppError.EmptyOutput,
        AppError.InferenceFailed,
        AppError.Timeout,
        AppError.ServerError -> listOf(ErrorAction.Retry)
    }
}
