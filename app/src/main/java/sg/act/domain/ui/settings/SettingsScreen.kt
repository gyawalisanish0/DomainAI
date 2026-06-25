package sg.act.domain.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sg.act.domain.BuildConfig
import sg.act.domain.R
import sg.act.domain.core.Diagnostics
import sg.act.domain.inference.ModelDownloader
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelSpec
import sg.act.domain.inference.OpenRouterClient
import sg.act.domain.privacy.DeviceCapabilities
import sg.act.domain.ui.components.ContextLengthRow
import sg.act.domain.ui.components.ThreadCountRow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    // Ask for notification permission once (Android 13+) so download progress can
    // be shown in the status bar. Downloads work regardless of the answer.
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importModel) }

    var pendingDownload by remember { mutableStateOf<ModelSpec?>(null) }
    pendingDownload?.let { spec ->
        DownloadConfirmDialog(
            spec = spec,
            onConfirm = { viewModel.downloadModel(spec); pendingDownload = null },
            onCancel = { pendingDownload = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimensionResource(R.dimen.space_l)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_l)),
        ) {
            SectionTitle(stringResource(R.string.settings_section_privacy))

            SwitchRow(
                title = stringResource(R.string.setting_killswitch_title),
                summary = stringResource(R.string.setting_killswitch_summary),
                checked = state.privacy.networkKillSwitch,
                onCheckedChange = viewModel::setKillSwitch,
            )
            SwitchRow(
                title = stringResource(R.string.setting_consent_title),
                summary = stringResource(R.string.setting_consent_summary),
                checked = state.privacy.cloudConsentGiven,
                onCheckedChange = viewModel::setConsent,
            )
            SwitchRow(
                title = stringResource(R.string.setting_redact_title),
                summary = stringResource(R.string.setting_redact_summary),
                checked = state.privacy.redactBeforeCloud,
                onCheckedChange = viewModel::setRedact,
            )
            SwitchRow(
                title = stringResource(R.string.setting_datalog_title),
                summary = stringResource(R.string.setting_datalog_summary),
                checked = state.privacy.allowDataLoggingModels,
                onCheckedChange = viewModel::setAllowDataLogging,
            )
            SwitchRow(
                title = stringResource(R.string.setting_crash_title),
                summary = stringResource(R.string.setting_crash_summary),
                checked = state.privacy.crashReportingEnabled,
                onCheckedChange = viewModel::setCrashReporting,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_model))
            ModelSection(
                state = state,
                onDownload = { pendingDownload = it },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onUnload = viewModel::unloadModel,
                onCancelDownload = viewModel::cancelDownload,
                onDismissTransfer = viewModel::dismissTransfer,
                onSelect = viewModel::selectModel,
                onDelete = viewModel::deleteModel,
                onSetGpu = viewModel::setGpuEnabled,
                onSetContext = viewModel::setContextTokens,
                onSetThreads = viewModel::setThreadCount,
                onBenchmark = viewModel::runBenchmark,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_cloud))
            CloudSection(
                state = state,
                onFetch = viewModel::fetchOpenRouterModels,
                onSelect = viewModel::selectOpenRouterModel,
                onSaveManual = viewModel::saveProvider,
                onDelete = viewModel::clearProvider,
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Debug builds only: export the app's own logcat to the share sheet.
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                SectionTitle(stringResource(R.string.settings_section_diagnostics))
                Text(
                    stringResource(R.string.diagnostics_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { Diagnostics.captureAndShare(context) }) {
                    Text(stringResource(R.string.action_share_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_l)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The single "Cloud" provider area. Exactly one provider can be active at a time.
 * When one is configured, only an uneditable summary + Delete is shown — the key
 * itself (stored encrypted) is never displayed. Otherwise the OpenRouter picker
 * and the advanced manual form are shown, and selecting/saving validates the
 * connection (a real round-trip) before persisting.
 */
@Composable
private fun CloudSection(
    state: SettingsUiState,
    onFetch: (String) -> Unit,
    onSelect: (String, OpenRouterClient.FreeModel) -> Unit,
    onSaveManual: (String, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        if (state.providerValidating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                    strokeWidth = dimensionResource(R.dimen.stroke_thin),
                )
                Text(
                    stringResource(R.string.provider_validating),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        state.providerError?.let {
            Text(
                stringResource(R.string.provider_invalid, it),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.brand_blocked),
            )
        }

        if (state.hasProvider) {
            ActiveProviderCard(modelId = state.activeModelId, onDelete = onDelete)
        } else {
            OpenRouterSection(state = state, onFetch = onFetch, onSelect = onSelect)
            AdvancedProviderSection(onSave = onSaveManual)
        }
    }
}

@Composable
private fun ActiveProviderCard(modelId: String?, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colorResource(R.color.brand_cloud)
                .copy(alpha = integerResource(R.integer.alpha_container_soft_pct) / 100f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.space_m)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Text(
                stringResource(R.string.provider_active, modelId ?: "—"),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.brand_cloud),
            )
            Text(
                stringResource(R.string.provider_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDelete) {
                Text(stringResource(R.string.provider_delete))
            }
        }
    }
}

@Composable
private fun AdvancedProviderSection(
    onSave: (String, String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.advanced_endpoint_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            Text(
                stringResource(R.string.advanced_endpoint_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderForm(onSave = onSave)
        }
    }
}

@Composable
private fun ProviderForm(onSave: (String, String, String) -> Unit) {
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.setting_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.setting_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.setting_model)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onSave(baseUrl, apiKey, model) },
            enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
        ) {
            Text(stringResource(R.string.setting_save_provider))
        }
    }
}

@Composable
private fun ModelSection(
    state: SettingsUiState,
    onDownload: (ModelSpec) -> Unit,
    onImport: () -> Unit,
    onUnload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissTransfer: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetGpu: (Boolean) -> Unit,
    onSetContext: (Int) -> Unit,
    onSetThreads: (Int) -> Unit,
    onBenchmark: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        Text(
            stringResource(R.string.model_device_ram, state.totalRamMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelStatusLine(state.modelState)

        when (val t = state.transfer) {
            is ModelManager.TransferState.Downloading -> {
                val fraction = t.progress?.fraction ?: 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
                ) {
                    Text(
                        stringResource(
                            R.string.model_download_detail,
                            t.modelName,
                            (fraction * 100).toInt(),
                            formatBytes(t.progress?.bytesRead ?: 0L),
                            formatBytes(t.progress?.totalBytes ?: 0L),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.model_download_confirm_cancel))
                    }
                }
            }
            is ModelManager.TransferState.Importing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.model_importing, t.modelName),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            is ModelManager.TransferState.Failed -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
                ) {
                    Text(
                        stringResource(R.string.model_transfer_failed, t.modelName, t.message),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorResource(R.color.brand_blocked),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissTransfer) {
                        Text(stringResource(R.string.model_transfer_dismiss))
                    }
                }
            }
            ModelManager.TransferState.Idle -> {}
        }

        // Models already on the device (downloaded or imported).
        val busy = state.transfer is ModelManager.TransferState.Downloading ||
            state.transfer is ModelManager.TransferState.Importing
        Text(
            stringResource(R.string.model_installed_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.installed.isEmpty()) {
            Text(
                stringResource(R.string.model_none_installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (model in state.installed) {
                InstalledModelRow(
                    model = model,
                    enabled = !busy,
                    onSelect = { onSelect(model.fileName) },
                    onDelete = { onDelete(model.fileName) },
                )
            }
        }

        // Catalog models not yet downloaded. Already-installed presets drop out of
        // this list automatically and show in the installed list above.
        val installedNames = state.installed.map { it.fileName }.toSet()
        val available = state.catalog.filterNot { it.spec.fileName in installedNames }
        if (available.isNotEmpty()) {
            Text(
                stringResource(R.string.model_available_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_s)),
            )
            for (option in available) {
                ModelRow(
                    option = option,
                    enabled = !busy,
                    onDownload = { onDownload(option.spec) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
            OutlinedButton(onClick = onImport, enabled = !busy) {
                Text(stringResource(R.string.model_import))
            }
            if (state.modelState is ModelManager.State.Ready) {
                OutlinedButton(onClick = onUnload) {
                    Text(stringResource(R.string.model_unload))
                }
            }
        }

        // Performance: GPU offload toggle + speed benchmark (data over vibes).
        HorizontalDivider(modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_xs)))
        SwitchRow(
            title = stringResource(R.string.setting_gpu_title),
            summary = stringResource(R.string.setting_gpu_summary),
            checked = state.gpuEnabled,
            onCheckedChange = onSetGpu,
        )
        ContextLengthRow(
            chosenTokens = state.contextTokens,
            effectiveTokens = state.effectiveContextTokens,
            options = state.contextOptions,
            onSelect = onSetContext,
        )
        ThreadCountRow(
            chosenThreads = state.threadCount,
            effectiveThreads = state.effectiveThreads,
            options = state.threadOptions,
            onSelect = onSetThreads,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            OutlinedButton(
                onClick = onBenchmark,
                enabled = state.modelState is ModelManager.State.Ready && !state.benchmarkRunning,
            ) {
                Text(stringResource(R.string.action_benchmark))
            }
            if (state.benchmarkRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                    strokeWidth = dimensionResource(R.dimen.stroke_thin),
                )
                Text(stringResource(R.string.benchmark_running), style = MaterialTheme.typography.labelMedium)
            }
        }
        state.benchmark?.let { r ->
            Text(
                stringResource(
                    R.string.benchmark_result,
                    String.format(Locale.US, "%.1f", r.genTps),
                    r.prefillMs,
                    r.genTokens,
                    r.detail ?: "—",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.brand_local),
            )
        }
    }
}

@Composable
private fun InstalledModelRow(
    model: sg.act.domain.inference.InstalledModel,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
            ) {
                Text(
                    stringResource(
                        if (model.source == sg.act.domain.data.local.ModelSource.IMPORT) {
                            R.string.model_source_import
                        } else {
                            R.string.model_source_download
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatBytes(model.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (model.isActive) {
            Text(
                stringResource(R.string.model_in_use),
                style = MaterialTheme.typography.labelMedium,
                color = colorResource(R.color.brand_local),
            )
        } else {
            OutlinedButton(onClick = onSelect, enabled = enabled) {
                Text(stringResource(R.string.model_use))
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.history_delete),
                tint = colorResource(R.color.brand_blocked),
                modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
            )
        }
    }
}

@Composable
private fun ModelStatusLine(modelState: ModelManager.State) {
    val text = when (modelState) {
        is ModelManager.State.NotLoaded -> stringResource(R.string.model_state_none)
        is ModelManager.State.Loading -> stringResource(R.string.model_state_loading, modelState.modelName)
        is ModelManager.State.Ready -> {
            val base = stringResource(R.string.model_state_ready, modelState.modelName)
            modelState.detail?.let { "$base · $it" } ?: base
        }
        is ModelManager.State.Error -> stringResource(R.string.model_state_error, modelState.message)
    }
    val color = when (modelState) {
        is ModelManager.State.Ready -> colorResource(R.color.brand_local)
        is ModelManager.State.Error -> colorResource(R.color.brand_blocked)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
    ) {
        if (modelState is ModelManager.State.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                strokeWidth = dimensionResource(R.dimen.stroke_thin),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun ModelRow(
    option: ModelOption,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(option.spec.displayName, style = MaterialTheme.typography.bodyMedium)
            SuitabilityChip(option.suitability)
        }
        Text(
            formatBytes(option.spec.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDownload,
            enabled = enabled && option.suitability != DeviceCapabilities.Suitability.INSUFFICIENT,
        ) {
            Text(stringResource(R.string.model_download))
        }
    }
}

@Composable
private fun SuitabilityChip(suitability: DeviceCapabilities.Suitability) {
    val (labelRes, color) = when (suitability) {
        DeviceCapabilities.Suitability.RECOMMENDED ->
            R.string.suitability_recommended to colorResource(R.color.brand_local)
        DeviceCapabilities.Suitability.HEAVY ->
            R.string.suitability_heavy to colorResource(R.color.brand_cloud)
        DeviceCapabilities.Suitability.INSUFFICIENT ->
            R.string.suitability_insufficient to colorResource(R.color.brand_blocked)
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(labelRes)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = color,
        ),
    )
}

@Composable
private fun DownloadConfirmDialog(
    spec: ModelSpec,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstUrl = spec.urls.firstOrNull().orEmpty()
    val host = Regex("^https?://([^/]+)/").find(firstUrl)?.groupValues?.get(1) ?: firstUrl
    val hostLabel = if (spec.urls.size > 1) {
        "$host (+${spec.urls.size - 1} fallback mirrors)"
    } else {
        host
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.model_download_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.model_download_confirm_body,
                    spec.displayName,
                    formatBytes(spec.sizeBytes),
                    hostLabel,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.model_download_confirm_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.model_download_confirm_cancel)) }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / 1_000_000.0
    return String.format(Locale.US, "%.0f MB", mb)
}

@Composable
private fun OpenRouterSection(
    state: SettingsUiState,
    onFetch: (String) -> Unit,
    onSelect: (String, OpenRouterClient.FreeModel) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        Text(
            stringResource(R.string.openrouter_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.openrouter_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Button(
                onClick = { onFetch(apiKey) },
                enabled = apiKey.isNotBlank() && !state.openRouterLoading,
            ) {
                Text(stringResource(R.string.openrouter_fetch))
            }
            if (state.openRouterLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                    strokeWidth = dimensionResource(R.dimen.stroke_thin),
                )
                Text(
                    stringResource(R.string.openrouter_loading),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        state.openRouterError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.brand_blocked))
        }
        if (state.openRouterModels.isEmpty() && !state.openRouterLoading) {
            Text(
                stringResource(R.string.openrouter_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (model in state.openRouterModels) {
            OpenRouterRow(
                model = model,
                active = model.id == state.activeModelId,
                enabled = !state.providerValidating,
                onUse = { onSelect(apiKey, model) },
            )
        }
    }
}

@Composable
private fun OpenRouterRow(
    model: OpenRouterClient.FreeModel,
    active: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
            ) {
                Text(
                    stringResource(R.string.openrouter_ctx, model.contextLength / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.logsData) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.openrouter_logs_tag)) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledLabelColor = colorResource(R.color.brand_blocked),
                        ),
                    )
                }
            }
        }
        Button(onClick = onUse, enabled = enabled && !active) {
            Text(stringResource(R.string.openrouter_use))
        }
    }
}
