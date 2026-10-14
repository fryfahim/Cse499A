package edu.cse499a.rocobangla.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ErrorAction
import edu.cse499a.rocobangla.core.ErrorMapping
import edu.cse499a.rocobangla.data.SideCheck
import edu.cse499a.rocobangla.data.SideCheckLevel
import edu.cse499a.rocobangla.ui.analyze.ConnectionStatus
import edu.cse499a.rocobangla.ui.theme.BanglaBodyStyle
import edu.cse499a.rocobangla.ui.theme.LocalStatusColors
import edu.cse499a.rocobangla.ui.theme.Spacing

/** A white card with a subtle border and no shadow. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.NotConfigured -> stringResource(R.string.conn_not_configured)
    ConnectionStatus.Checking -> stringResource(R.string.conn_checking)
    ConnectionStatus.Ready -> stringResource(R.string.conn_ready)
    is ConnectionStatus.Busy -> stringResource(R.string.conn_busy, status.queueLength)
    ConnectionStatus.ModelLoading -> stringResource(R.string.conn_loading)
    ConnectionStatus.ModelError -> stringResource(R.string.conn_model_error)
    ConnectionStatus.Offline -> stringResource(R.string.conn_offline)
}

/** Live connection state from /health. Tapping opens Settings. */
@Composable
fun ConnectionChip(status: ConnectionStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    val dot: Color = when (status) {
        ConnectionStatus.Ready -> colors.success
        is ConnectionStatus.Busy, ConnectionStatus.ModelLoading, ConnectionStatus.Checking -> colors.warning
        ConnectionStatus.Offline, ConnectionStatus.ModelError -> MaterialTheme.colorScheme.error
        ConnectionStatus.NotConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = connectionLabel(status)
    val description = stringResource(R.string.conn_cd, label)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = Spacing.touch)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Bangla + English disclaimer. Shown on Home and Result. */
@Composable
fun DisclaimerCard(disclaimerBn: String, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.neutralContainer,
    ) {
        Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = colors.onNeutralContainer)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(disclaimerBn, style = BanglaBodyStyle, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.disclaimer_en),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onNeutralContainer,
                )
            }
        }
    }
}

/** Result of the notebook's left/right consistency check. */
@Composable
fun SideCheckBanner(check: SideCheck, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    val (container, content, title) = when (check.level) {
        SideCheckLevel.Ok -> Triple(colors.successContainer, colors.onSuccessContainer, stringResource(R.string.side_ok_title))
        SideCheckLevel.Warning -> Triple(colors.warningContainer, colors.onWarningContainer, stringResource(R.string.side_warning_title))
        SideCheckLevel.NotChecked -> Triple(colors.neutralContainer, colors.onNeutralContainer, stringResource(R.string.side_not_checked_title))
    }
    val icon = when (check.level) {
        SideCheckLevel.Ok -> Icons.Filled.CheckCircle
        SideCheckLevel.Warning -> Icons.Filled.Warning
        SideCheckLevel.NotChecked -> Icons.Filled.Info
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = if (check.level == SideCheckLevel.Warning) BorderStroke(1.5.dp, colors.warning) else null,
    ) {
        Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(icon, contentDescription = null, tint = content)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
                if (check.message.isNotBlank()) {
                    Text(check.message, style = MaterialTheme.typography.bodySmall, color = content)
                }
            }
        }
    }
}

@Composable
fun errorMessage(error: AppError): String = stringResource(
    when (error) {
        AppError.InvalidImage -> R.string.err_invalid_image
        AppError.TooLarge -> R.string.err_too_large
        AppError.UnsupportedImageType -> R.string.err_unsupported_type
        AppError.ImageUnreadable -> R.string.err_image_unreadable
        AppError.ModelNotReady -> R.string.err_model_not_ready
        AppError.QueueFull -> R.string.err_queue_full
        AppError.EmptyOutput -> R.string.err_empty_output
        AppError.InferenceFailed -> R.string.err_inference_failed
        AppError.Unauthorized -> R.string.err_unauthorized
        AppError.JobNotFound -> R.string.err_job_not_found
        AppError.Offline -> R.string.err_offline
        AppError.Timeout -> R.string.err_timeout
        AppError.NotConfigured -> R.string.err_not_configured
        AppError.ServerError -> R.string.err_server_error
    },
)

/** A friendly error with its next steps. Never shows raw exceptions. */
@Composable
fun ErrorCard(
    error: AppError,
    onAction: (ErrorAction) -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.error_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        errorMessage(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            FlowButtons {
                ErrorMapping.actionsFor(error).forEachIndexed { index, action ->
                    val label = when (action) {
                        ErrorAction.Retry -> stringResource(R.string.retry)
                        ErrorAction.OpenSettings -> stringResource(R.string.open_settings)
                        ErrorAction.PickAnotherImage -> stringResource(R.string.pick_another)
                    }
                    if (index == 0) {
                        Button(onClick = { onAction(action) }, modifier = Modifier.heightIn(min = Spacing.touch)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.heightIn(min = Spacing.touch)) { Text(label) }
                    }
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Spacing.touch)) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }
}

/** Buttons that wrap onto new lines at large font sizes instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { content() }
}
