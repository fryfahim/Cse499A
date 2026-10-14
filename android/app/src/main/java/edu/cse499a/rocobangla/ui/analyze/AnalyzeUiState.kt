package edu.cse499a.rocobangla.ui.analyze

import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.data.AnalysisResult
import edu.cse499a.rocobangla.data.PickedImage

/** Connection chip states, driven by real /health calls. */
sealed interface ConnectionStatus {
    data object NotConfigured : ConnectionStatus
    data object Checking : ConnectionStatus
    data object Ready : ConnectionStatus
    data class Busy(val queueLength: Int) : ConnectionStatus
    data object ModelLoading : ConnectionStatus
    data object ModelError : ConnectionStatus
    data object Offline : ConnectionStatus

    val allowsAnalyze: Boolean get() = this == Ready || this is Busy
}

/** Idle → ImageSelected → Submitting → Queued/Running (polling) → Success | Error */
sealed interface AnalyzePhase {
    data object Idle : AnalyzePhase
    data object ImageSelected : AnalyzePhase
    data object Submitting : AnalyzePhase

    /** [position] 0 means not known yet (e.g. right after a process restore). */
    data class Queued(val jobId: String, val position: Int, val startedAtMillis: Long) : AnalyzePhase
    data class Running(val jobId: String, val startedAtMillis: Long) : AnalyzePhase
    data class Success(val result: AnalysisResult, val completedAtMillis: Long) : AnalyzePhase
    data class Error(val error: AppError) : AnalyzePhase
}

/** One-off messages shown in a snackbar. */
enum class Notice { StoppedWaiting }

data class AnalyzeUiState(
    val image: PickedImage? = null,
    val caption: String = "",
    val phase: AnalyzePhase = AnalyzePhase.Idle,
    val connection: ConnectionStatus = ConnectionStatus.Checking,
    /** Problem with the picked file, shown on the picker card. */
    val imageError: AppError? = null,
    val notice: Notice? = null,
) {
    val isWorking: Boolean
        get() = phase is AnalyzePhase.Submitting || phase is AnalyzePhase.Queued || phase is AnalyzePhase.Running

    val canAnalyze: Boolean
        get() = image != null && !isWorking && connection.allowsAnalyze
}
