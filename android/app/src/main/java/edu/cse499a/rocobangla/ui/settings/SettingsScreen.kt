package edu.cse499a.rocobangla.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import edu.cse499a.rocobangla.BuildConfig
import edu.cse499a.rocobangla.R
import edu.cse499a.rocobangla.core.UrlProblem
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.ui.components.FlowButtons
import edu.cse499a.rocobangla.ui.components.SectionCard
import edu.cse499a.rocobangla.ui.components.SectionTitle
import edu.cse499a.rocobangla.ui.components.errorMessage
import edu.cse499a.rocobangla.ui.theme.LocalStatusColors
import edu.cse499a.rocobangla.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val noticeText = when (state.notice) {
        SettingsNotice.Saved -> stringResource(R.string.notice_saved)
        SettingsNotice.QrInvalid -> stringResource(R.string.notice_qr_invalid)
        SettingsNotice.ScannerUnavailable -> stringResource(R.string.notice_scanner_unavailable)
        null -> null
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.onNoticeShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            ServerSection(
                state = state,
                onUrlChange = viewModel::onUrlChange,
                onKeyChange = viewModel::onKeyChange,
                onToggleKey = viewModel::onToggleKeyVisible,
                onScan = { scanQr(context, viewModel::onQrScanned, viewModel::onScannerUnavailable) },
                onSave = viewModel::onSave,
                onTest = viewModel::onTestConnection,
            )
            ModelSection()
            TextSection(
                title = stringResource(R.string.section_privacy),
                lines = listOf(stringResource(R.string.privacy_text), stringResource(R.string.privacy_key)),
            )
            TextSection(
                title = stringResource(R.string.section_limitations),
                lines = listOf(
                    stringResource(R.string.limit_1),
                    stringResource(R.string.limit_2),
                    stringResource(R.string.limit_3),
                    stringResource(R.string.limit_4),
                    stringResource(R.string.limit_5),
                    stringResource(R.string.limit_6),
                ),
                bullets = true,
            )
            TextSection(
                title = stringResource(R.string.section_about),
                lines = listOf(
                    stringResource(R.string.version, BuildConfig.VERSION_NAME),
                    stringResource(R.string.font_license),
                ),
            )
        }
    }
}

/** Google code scanner: runs in Play services, so the app needs no camera permission. */
private fun scanQr(context: Context, onResult: (String?) -> Unit, onUnavailable: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode -> onResult(barcode.rawValue) }
        .addOnFailureListener { onUnavailable() }
}

@Composable
private fun ServerSection(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onToggleKey: () -> Unit,
    onScan: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard {
        SectionTitle(stringResource(R.string.section_server))
        Text(
            stringResource(R.string.server_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touch)) {
            Text(stringResource(R.string.scan_qr))
        }
        val urlError = when (state.urlProblem) {
            UrlProblem.Empty -> stringResource(R.string.url_err_empty)
            UrlProblem.NotHttps -> stringResource(R.string.url_err_https)
            UrlProblem.Malformed -> stringResource(R.string.url_err_malformed)
            null -> null
        }
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.url_label)) },
            placeholder = { Text(stringResource(R.string.url_placeholder)) },
            isError = urlError != null,
            supportingText = if (urlError != null) {
                { Text(urlError) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.key_label)) },
            isError = state.keyMissing,
            supportingText = if (state.keyMissing) {
                { Text(stringResource(R.string.key_err_missing)) }
            } else {
                null
            },
            singleLine = true,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            trailingIcon = {
                val cd = stringResource(if (state.keyVisible) R.string.hide_key_cd else R.string.show_key_cd)
                TextButton(onClick = onToggleKey, modifier = Modifier.semantics { this.contentDescription = cd }) {
                    Text(stringResource(if (state.keyVisible) R.string.hide else R.string.show))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowButtons {
            Button(onClick = onTest, enabled = state.test != ConnectionTest.Running, modifier = Modifier.heightIn(min = Spacing.touch)) {
                Text(stringResource(R.string.test_connection))
            }
            OutlinedButton(onClick = onSave, modifier = Modifier.heightIn(min = Spacing.touch)) {
                Text(stringResource(R.string.save))
            }
        }
        TestResult(state.test)
    }
}

@Composable
private fun TestResult(test: ConnectionTest) {
    val colors = LocalStatusColors.current
    val (text, color) = when (test) {
        ConnectionTest.NotRun -> return
        ConnectionTest.Running -> stringResource(R.string.test_running) to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionTest.Failed -> errorMessage(test.error) to MaterialTheme.colorScheme.error
        is ConnectionTest.Passed -> when (test.modelStatus) {
            ModelStatus.Ready ->
                if (test.queueLength > 0) {
                    stringResource(R.string.test_ok_ready_busy, test.queueLength) to colors.success
                } else {
                    stringResource(R.string.test_ok_ready) to colors.success
                }
            ModelStatus.Loading -> stringResource(R.string.test_ok_loading) to colors.warning
            ModelStatus.Error -> stringResource(R.string.test_ok_error) to MaterialTheme.colorScheme.error
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ModelSection() {
    SectionCard {
        SectionTitle(stringResource(R.string.section_model))
        InfoRow(stringResource(R.string.model_base_label), stringResource(R.string.model_base))
        InfoRow(stringResource(R.string.model_finetune_label), stringResource(R.string.model_finetune))
        InfoRow(stringResource(R.string.model_data_label), stringResource(R.string.model_data))
        InfoRow(stringResource(R.string.model_task_label), stringResource(R.string.model_task))
        InfoRow(stringResource(R.string.model_decoding_label), stringResource(R.string.model_decoding))
        Text(
            stringResource(R.string.model_not_chat),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TextSection(title: String, lines: List<String>, bullets: Boolean = false) {
    SectionCard {
        SectionTitle(title)
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (bullets) Text("•", style = MaterialTheme.typography.bodyMedium)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
