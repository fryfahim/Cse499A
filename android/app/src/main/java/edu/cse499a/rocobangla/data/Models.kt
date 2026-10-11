package edu.cse499a.rocobangla.data

import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ErrorMapping

/** Success or a user-facing [AppError]. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

enum class ModelStatus { Ready, Loading, Error }

data class ServerHealth(val status: ModelStatus, val queueLength: Int, val busy: Boolean)

data class JobTicket(val jobId: String, val position: Int)

sealed interface JobState {
    /** [position] is the 1-based place in line, counting a running job as ahead. */
    data class Queued(val position: Int) : JobState
    data object Running : JobState
    data class Completed(val result: AnalysisResult) : JobState
    data class Failed(val error: AppError) : JobState
}

enum class AnalysisMode { Caption, ImageOnly }

enum class SideCheckLevel { Ok, Warning, NotChecked }

data class SideCheck(val level: SideCheckLevel, val message: String)

/** The model output exactly as the server returned it (already trimmed there). */
data class AnalysisResult(
    val banglaText: String,
    val mode: AnalysisMode,
    val captionUsed: String,
    val sideCheck: SideCheck,
    val disclaimerBn: String,
    val elapsedMs: Long,
)

/** An image the user picked, described without decoding or changing it. */
data class PickedImage(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val widthPx: Int,
    val heightPx: Int,
)

fun HealthDto.toModel(): ServerHealth = ServerHealth(
    status = when (status) {
        "ready" -> ModelStatus.Ready
        "loading" -> ModelStatus.Loading
        else -> ModelStatus.Error
    },
    queueLength = queueLength,
    busy = busy,
)

fun JobDto.toModel(): JobState = when (status) {
    "queued" -> JobState.Queued(position)
    "running" -> JobState.Running
    "completed" -> result?.let { JobState.Completed(it.toModel()) }
        ?: JobState.Failed(AppError.ServerError)
    "failed" -> JobState.Failed(ErrorMapping.fromServerCode(error?.code, null))
    else -> JobState.Failed(AppError.ServerError)
}

fun ResultDto.toModel(): AnalysisResult = AnalysisResult(
    banglaText = banglaText.trim(),
    mode = if (mode == "caption") AnalysisMode.Caption else AnalysisMode.ImageOnly,
    captionUsed = captionUsed,
    sideCheck = SideCheck(
        level = when (sideCheck.level) {
            "ok" -> SideCheckLevel.Ok
            "warning" -> SideCheckLevel.Warning
            else -> SideCheckLevel.NotChecked
        },
        message = sideCheck.message,
    ),
    disclaimerBn = disclaimerBn,
    elapsedMs = elapsedMs,
)
