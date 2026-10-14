package edu.cse499a.rocobangla.ui.analyze

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.data.PickedImage
import edu.cse499a.rocobangla.ui.theme.LocalReduceMotion
import edu.cse499a.rocobangla.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Real queue position and status from polling; no fake percentage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzingScreen(phase: AnalyzePhase, image: PickedImage?, onCancel: () -> Unit) {
    val status = when (phase) {
        AnalyzePhase.Submitting -> stringResource(R.string.status_uploading)
        is AnalyzePhase.Queued -> when {
            phase.position <= 0 -> stringResource(R.string.status_checking)
            phase.position == 1 -> stringResource(R.string.status_queue_next)
            else -> stringResource(R.string.status_queue_ahead, phase.position - 1)
        }
        is AnalyzePhase.Running -> stringResource(R.string.status_generating)
        else -> ""
    }
    val startedAt = when (phase) {
        is AnalyzePhase.Queued -> phase.startedAtMillis
        is AnalyzePhase.Running -> phase.startedAtMillis
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analyzing_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image.uri,
                    contentDescription = stringResource(R.string.image_preview_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            if (LocalReduceMotion.current) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }
            Text(
                text = status,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (startedAt != null) ElapsedText(startedAt)
            Text(
                stringResource(R.string.analyzing_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touch),
            ) {
                Text(stringResource(R.string.stop_waiting))
            }
            Text(
                stringResource(R.string.stop_waiting_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ElapsedText(startedAtMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - startedAtMillis) / 1000).coerceAtLeast(0)
    Text(
        stringResource(R.string.elapsed, "%d:%02d".format(seconds / 60, seconds % 60)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
