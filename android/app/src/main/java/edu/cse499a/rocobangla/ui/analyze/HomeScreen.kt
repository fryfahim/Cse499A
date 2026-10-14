package edu.cse499a.rocobangla.ui.analyze

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.core.AppConfig
import edu.cse499a.rocobangla.core.ErrorAction
import edu.cse499a.rocobangla.data.PickedImage
import edu.cse499a.rocobangla.ui.components.DisclaimerCard
import edu.cse499a.rocobangla.ui.components.ErrorCard
import edu.cse499a.rocobangla.ui.components.ConnectionChip
import edu.cse499a.rocobangla.ui.components.FlowButtons
import edu.cse499a.rocobangla.ui.components.SectionCard
import edu.cse499a.rocobangla.ui.components.SectionTitle
import edu.cse499a.rocobangla.ui.components.errorMessage
import edu.cse499a.rocobangla.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AnalyzeUiState,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onCaptionChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onErrorAction: (ErrorAction) -> Unit,
    onDismissError: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectionChip(status = state.connection, onClick = onOpenSettings)

            ImagePickerCard(
                image = state.image,
                imageError = state.imageError?.let { errorMessage(it) },
                onPick = onPickImage,
                onRemove = onRemoveImage,
            )

            CaptionField(caption = state.caption, onChange = onCaptionChange)

            val phase = state.phase
            if (phase is AnalyzePhase.Error) {
                ErrorCard(error = phase.error, onAction = onErrorAction, onDismiss = onDismissError)
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = onAnalyze,
                    enabled = state.canAnalyze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.analyze), style = MaterialTheme.typography.titleMedium)
                }
                val hint = when {
                    state.image == null -> stringResource(R.string.analyze_disabled_no_image)
                    !state.connection.allowsAnalyze -> stringResource(R.string.analyze_disabled_connection)
                    else -> null
                }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DisclaimerCard(disclaimerBn = stringResource(R.string.disclaimer_bn))
        }
    }
}

@Composable
private fun ImagePickerCard(
    image: PickedImage?,
    imageError: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard {
        SectionTitle(stringResource(R.string.picker_title))
        if (image == null) {
            Text(
                stringResource(R.string.picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPick, modifier = Modifier.heightIn(min = Spacing.touch)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.pick_image), modifier = Modifier.padding(start = Spacing.sm))
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = image.uri,
                        contentDescription = stringResource(R.string.image_preview_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(image.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(
                        R.string.image_meta,
                        Formatter.formatShortFileSize(context, image.sizeBytes.coerceAtLeast(0)),
                        image.widthPx,
                        image.heightPx,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowButtons {
                OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = Spacing.touch)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(stringResource(R.string.replace), modifier = Modifier.padding(start = Spacing.sm))
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.heightIn(min = Spacing.touch)) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.remove), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
        }
        if (imageError != null) {
            Text(imageError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CaptionField(caption: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedTextField(
            value = caption,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.caption_label)) },
            supportingText = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.caption_helper), modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.caption_counter, caption.length, AppConfig.MAX_CAPTION_CHARS))
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        if (caption.isBlank()) {
            Text(
                stringResource(R.string.caption_empty_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
