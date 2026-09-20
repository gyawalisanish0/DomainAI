package sg.act.domain.ui.models

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import sg.act.domain.R
import sg.act.domain.inference.InstalledModel
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelSpec
import sg.act.domain.privacy.DeviceCapabilities
import sg.act.domain.ui.components.ContextLengthRow
import sg.act.domain.ui.components.ThreadCountRow
import sg.act.domain.ui.settings.ModelOption
import sg.act.domain.ui.settings.SettingsUiState
import sg.act.domain.ui.settings.SettingsViewModel

/**
 * On-device models: what is installed, what can be downloaded, and how the
 * engine is configured to run them.
 *
 * This was a section inside Settings, behind one expandable row. It carried the
 * installed list, the download catalog, import, the GPU toggle, context length,
 * thread count and the benchmark — which is a screen's worth of work pretending
 * to be a settings row. Giving it its own destination lets Settings stay one
 * line per row, and lets this grow (per-model defaults, storage management)
 * without pushing everything else further down a scroll.
 *
 * It shares [SettingsViewModel] with the Settings screen rather than owning a
 * second one: both render the same model state, and two view models collecting
 * the same flows would drift apart the moment one of them missed an emission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Notification permission (Android 13+) so download progress can appear in
    // the status bar. Asked for here, where downloads actually start, rather
    // than on opening Settings. Downloads work regardless of the answer.
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
                title = { Text(stringResource(R.string.models_title)) },
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
        ) {
            ModelBody(
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
                onSetReuseCache = viewModel::setReusePromptCache,
                onSetStrictAffinity = viewModel::setStrictAffinity,
                onSetHighPriority = viewModel::setHighPriority,
            )
        }
    }
}

@Composable
private fun ModelBody(
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
    onSetReuseCache: (Boolean) -> Unit,
    onSetStrictAffinity: (Boolean) -> Unit,
    onSetHighPriority: (Boolean) -> Unit,
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
        Text(
            stringResource(R.string.engine_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        // The engine decides these for you by default. They are here because an
        // automatic mechanism that cannot be switched off is one you cannot debug,
        // and because a heuristic is wrong on some hardware — the benchmark below
        // turns "which is faster on my phone" from an argument into a measurement.
        SwitchRow(
            title = stringResource(R.string.setting_cache_title),
            summary = stringResource(R.string.setting_cache_summary),
            checked = state.reusePromptCache,
            onCheckedChange = onSetReuseCache,
        )
        SwitchRow(
            title = stringResource(R.string.setting_strict_title),
            summary = stringResource(R.string.setting_strict_summary),
            checked = state.strictAffinity,
            onCheckedChange = onSetStrictAffinity,
        )
        SwitchRow(
            title = stringResource(R.string.setting_priority_title),
            summary = stringResource(R.string.setting_priority_summary),
            checked = state.highPriority,
            onCheckedChange = onSetHighPriority,
        )
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

/**
 * The switch row used inside this screen's padded body — hence no horizontal
 * padding of its own. Settings' top-level switches use `SettingsSwitchRow`,
 * which sits directly on a group surface and pads itself.
 */
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
