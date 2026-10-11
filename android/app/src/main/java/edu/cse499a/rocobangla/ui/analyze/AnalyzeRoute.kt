package edu.cse499a.rocobangla.ui.analyze

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.core.ErrorAction
import edu.cse499a.rocobangla.ui.theme.LocalReduceMotion

private enum class FlowScreen { Home, Analyzing, Result }

private fun AnalyzeUiState.screen(): FlowScreen = when (phase) {
    AnalyzePhase.Submitting, is AnalyzePhase.Queued, is AnalyzePhase.Running -> FlowScreen.Analyzing
    is AnalyzePhase.Success -> FlowScreen.Result
    else -> FlowScreen.Home
}

/** Home, Analyzing and Result are views of one state machine, so they share
 *  this destination and one ViewModel; the phase picks the screen. */
@Composable
fun AnalyzeRoute(viewModel: AnalyzeViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reduceMotion = LocalReduceMotion.current
    val snackbar = remember { SnackbarHostState() }

    // Poll /health only while this screen is visible.
    LifecycleStartEffect(viewModel) {
        viewModel.startHealthMonitor()
        onStopOrDispose { viewModel.stopHealthMonitor() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onImagePicked(uri?.toString())
    }
    val pickImage: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val stoppedMessage = stringResource(R.string.notice_stopped)
    LaunchedEffect(state.notice) {
        if (state.notice == Notice.StoppedWaiting) {
            snackbar.showSnackbar(stoppedMessage)
            viewModel.onNoticeShown()
        }
    }

    BackHandler(enabled = state.isWorking) { viewModel.onCancelWaiting() }
    BackHandler(enabled = state.phase is AnalyzePhase.Success) { viewModel.onCloseResult() }

    AnimatedContent(
        targetState = state,
        contentKey = { it.screen() },
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
            }
        },
        label = "analyze-flow",
    ) { shown ->
        // `shown` is the state this screen was entered with; an outgoing
        // screen keeps its own snapshot while it fades out.
        when (val phase = shown.phase) {
            is AnalyzePhase.Success -> ResultScreen(
                state = shown,
                result = phase.result,
                completedAtMillis = phase.completedAtMillis,
                snackbar = snackbar,
                onClose = viewModel::onCloseResult,
                onAnalyzeAgain = viewModel::onAnalyzeAgain,
                onNewImage = viewModel::onNewImage,
            )
            AnalyzePhase.Submitting, is AnalyzePhase.Queued, is AnalyzePhase.Running -> AnalyzingScreen(
                phase = phase,
                image = shown.image,
                onCancel = viewModel::onCancelWaiting,
            )
            else -> HomeScreen(
                state = shown,
                snackbar = snackbar,
                onOpenSettings = onOpenSettings,
                onPickImage = pickImage,
                onRemoveImage = viewModel::onRemoveImage,
                onCaptionChange = viewModel::onCaptionChange,
                onAnalyze = viewModel::onAnalyze,
                onErrorAction = { action ->
                    when (action) {
                        ErrorAction.Retry -> viewModel.onRetry()
                        ErrorAction.OpenSettings -> {
                            viewModel.onDismissError()
                            onOpenSettings()
                        }
                        ErrorAction.PickAnotherImage -> {
                            viewModel.onDismissError()
                            pickImage()
                        }
                    }
                },
                onDismissError = viewModel::onDismissError,
            )
        }
    }
}
