package edu.cse499a.rocobangla.ui.analyze

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.data.AnalysisMode
import edu.cse499a.rocobangla.data.AnalysisResult
import edu.cse499a.rocobangla.ui.components.DisclaimerCard
import edu.cse499a.rocobangla.ui.components.FlowButtons
import edu.cse499a.rocobangla.ui.components.SectionCard
import edu.cse499a.rocobangla.ui.components.SideCheckBanner
import edu.cse499a.rocobangla.ui.theme.BanglaResultStyle
import edu.cse499a.rocobangla.ui.theme.LocalReduceMotion
import edu.cse499a.rocobangla.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    state: AnalyzeUiState,
    result: AnalysisResult,
    completedAtMillis: Long,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onAnalyzeAgain: () -> Unit,
    onNewImage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied)
    val copyLabel = stringResource(R.string.copy_label)
    val copySuffix = stringResource(R.string.copy_suffix)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.result_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            state.image?.let { image -> CollapsibleThumbnail(uri = image.uri) }

            SectionCard {
                Text(
                    stringResource(R.string.result_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Shown exactly as generated (the server only trims whitespace).
                SelectionContainer {
                    Text(result.banglaText, style = BanglaResultStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            SideCheckBanner(result.sideCheck)

            ResultMeta(result, completedAtMillis)

            DisclaimerCard(disclaimerBn = result.disclaimerBn)

            FlowButtons {
                Button(
                    onClick = {
                        copyToClipboard(context, copyLabel, "${result.banglaText}\n\n${result.disclaimerBn}\n$copySuffix")
                        // Android 13+ shows its own copy confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            scope.launch { snackbar.showSnackbar(copiedMessage) }
                        }
                    },
                    modifier = Modifier.heightIn(min = Spacing.touch),
                ) { Text(stringResource(R.string.copy)) }
                OutlinedButton(onClick = onAnalyzeAgain, modifier = Modifier.heightIn(min = Spacing.touch)) {
                    Text(stringResource(R.string.analyze_again))
                }
                OutlinedButton(onClick = onNewImage, modifier = Modifier.heightIn(min = Spacing.touch)) {
                    Text(stringResource(R.string.new_image))
                }
            }
        }
    }
}

@Composable
private fun CollapsibleThumbnail(uri: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = Spacing.touch)) {
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            Text(
                stringResource(if (expanded) R.string.hide_image else R.string.show_image),
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(),
        ) {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.image_preview_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
    }
}

@Composable
private fun ResultMeta(result: AnalysisResult, completedAtMillis: Long) {
    val timestamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(completedAtMillis))
    val seconds = String.format(Locale.getDefault(), "%.1f s", result.elapsedMs / 1000.0)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowButtons {
            Tag(stringResource(if (result.mode == AnalysisMode.Caption) R.string.mode_caption else R.string.mode_image_only))
            Tag(stringResource(R.string.took, seconds))
            Tag(timestamp)
        }
        if (result.captionUsed.isNotBlank()) {
            Text(
                "${stringResource(R.string.caption_used_label)}: ${result.captionUsed}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
