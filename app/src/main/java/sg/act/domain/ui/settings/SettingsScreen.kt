package sg.act.domain.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import sg.act.domain.BuildConfig
import sg.act.domain.R
import sg.act.domain.core.Diagnostics
import sg.act.domain.core.SystemInfo
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelProfile
import sg.act.domain.inference.OpenRouterClient
import sg.act.domain.inference.ProviderType
import sg.act.domain.inference.SpaceClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                .padding(horizontal = dimensionResource(R.dimen.space_l))
                .padding(bottom = dimensionResource(R.dimen.space_xl)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            SettingsHeader(stringResource(R.string.settings_section_privacy))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_killswitch_title),
                    checked = state.privacy.networkKillSwitch,
                    onCheckedChange = viewModel::setKillSwitch,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_consent_title),
                    checked = state.privacy.cloudConsentGiven,
                    onCheckedChange = viewModel::setConsent,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_redact_title),
                    checked = state.privacy.redactBeforeCloud,
                    onCheckedChange = viewModel::setRedact,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_datalog_title),
                    subtitle = stringResource(R.string.setting_datalog_summary),
                    checked = state.privacy.allowDataLoggingModels,
                    onCheckedChange = viewModel::setAllowDataLogging,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.setting_crash_title),
                    checked = state.privacy.crashReportingEnabled,
                    onCheckedChange = viewModel::setCrashReporting,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                // Everything the five rows above used to say in permanent subtitles,
                // plus the old page footer, in one place nobody has to read.
                SettingsDisclosure(
                    label = stringResource(R.string.privacy_about_label),
                    body = stringResource(R.string.privacy_about_body),
                )
            }

            SettingsHeader(stringResource(R.string.settings_group_models))
            // A destination, not an expander: the installed list, the download
            // catalog, import, GPU, context, threads and the benchmark are a
            // screen's worth of work, and inlining them here is what made this
            // page endless.
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.settings_section_model),
                    status = modelStatus(state),
                    onClick = onOpenModels,
                )
            }

            SettingsHeader(stringResource(R.string.settings_section_cloud))
            SettingsSection(
                title = stringResource(R.string.settings_section_my_server),
                status = spaceStatus(state),
            ) {
                SpaceSection(
                    state = state,
                    onConnect = viewModel::connectSpace,
                    onLoadModel = viewModel::loadSpaceModel,
                    onRefresh = viewModel::refreshSpaceCatalog,
                    onDisconnect = viewModel::disconnectSpace,
                )
            }
            SettingsSection(
                title = stringResource(R.string.settings_section_cloud_api),
                status = cloudStatus(state),
            ) {
                OpenRouterSection(
                    state = state,
                    onFetch = viewModel::fetchOpenRouterModels,
                    onSelect = viewModel::selectOpenRouterModel,
                    onRemoveKey = viewModel::removeOpenRouterKey,
                )
                AdvancedProviderSection(onSave = viewModel::saveProvider)
            }
            SettingsSection(
                title = stringResource(R.string.settings_section_profiles),
                status = profilesStatus(state),
            ) {
                SavedProfilesSection(
                    state = state,
                    onSwitch = viewModel::switchProfile,
                    onDeactivate = viewModel::deactivateProfile,
                    onDelete = viewModel::deleteProfile,
                    onRename = viewModel::renameProfile,
                )
            }
            // Deliberately outside the sections: validation is triggered from more
            // than one of them, and feedback nobody can see is not feedback.
            if (state.providerValidating) {
                Row(
                    modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_l)),
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
                    modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.space_l)),
                )
            }

            SettingsHeader(stringResource(R.string.settings_group_about))
            SettingsSection(
                title = stringResource(R.string.settings_section_system),
                status = state.systemInfo?.device,
                onExpand = viewModel::refreshSystemInfo,
            ) {
                SystemInfoSection(
                    info = state.systemInfo,
                    onRefresh = viewModel::refreshSystemInfo,
                )
            }

            // Debug builds only: export the app's own logcat to the share sheet.
            if (BuildConfig.DEBUG) {
                SettingsSection(
                    title = stringResource(R.string.settings_section_diagnostics),
                    status = stringResource(R.string.diagnostics_summary),
                ) {
                    OutlinedButton(onClick = { Diagnostics.captureAndShare(context) }) {
                        Text(stringResource(R.string.action_share_diagnostics))
                    }
                }
            }
        }
    }
}

/**
 * Status lines for the collapsed sections.
 *
 * A collapsed section that says only its own name is worse than no section at
 * all: it hides information and offers nothing back. Each of these answers the
 * question the section exists for — which model is loaded, whether the server is
 * connected — so the common case needs no tap at all.
 */
@Composable
private fun modelStatus(state: SettingsUiState): String = when (val model = state.modelState) {
    is ModelManager.State.Ready ->
        listOfNotNull(model.modelName, model.detail).joinToString(" \u00b7 ")
    is ModelManager.State.Loading -> stringResource(R.string.settings_status_model_loading)
    is ModelManager.State.Error -> stringResource(R.string.settings_status_model_error)
    ModelManager.State.NotLoaded ->
        if (state.installed.isEmpty()) {
            stringResource(R.string.settings_status_model_none)
        } else {
            stringResource(R.string.settings_status_installed, state.installed.size)
        }
}

@Composable
private fun spaceStatus(state: SettingsUiState): String = when {
    state.spaceConnected ->
        stringResource(R.string.settings_status_space_connected, state.spaceUrlPreview)
    state.spaceCredentialsSaved ->
        stringResource(R.string.settings_status_space_saved, state.spaceUrlPreview)
    else -> stringResource(R.string.settings_status_space_none)
}

@Composable
private fun cloudStatus(state: SettingsUiState): String = stringResource(
    if (state.orKeySaved) R.string.settings_status_cloud_saved
    else R.string.settings_status_cloud_none,
)

@Composable
private fun profilesStatus(state: SettingsUiState): String {
    if (state.savedProfiles.isEmpty()) {
        return stringResource(R.string.settings_status_profiles_none)
    }
    val active = state.savedProfiles.firstOrNull { it.id == state.activeProfileId }
    return if (active != null) {
        stringResource(R.string.settings_status_profiles, state.savedProfiles.size, active.name)
    } else {
        stringResource(R.string.settings_status_profiles_inactive, state.savedProfiles.size)
    }
}

/**
 * The body of Settings → System info: what the app detected and how it decided
 * to run. The enclosing [SettingsSection] owns the collapse and re-samples on
 * open, so this is only the readings.
 */
@Composable
private fun SystemInfoSection(
    info: SystemInfo?,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    if (info == null) return

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs))) {
        InfoGroup(stringResource(R.string.system_group_device))
        InfoRow(stringResource(R.string.system_row_model), info.device)
        InfoRow(stringResource(R.string.system_row_android), info.android)
        InfoRow(stringResource(R.string.system_row_abi), info.abi)
        InfoRow(stringResource(R.string.system_row_app), info.appVersion)

        InfoGroup(stringResource(R.string.system_group_cpu))
        InfoRow(stringResource(R.string.system_row_cores), info.cores.toString())
        InfoRow(
            stringResource(R.string.system_row_dotprod),
            when (info.hasDotprod) {
                true -> stringResource(R.string.system_value_yes)
                false -> stringResource(R.string.system_value_no)
                null -> stringResource(R.string.system_value_unknown)
            },
        )
        // The full hwcap word, not just the bit the app cares about: a short,
        // plausible list distinguishes an older CPU from a parsing bug.
        InfoRow(
            label = stringResource(R.string.system_row_features),
            value = listOfNotNull(info.hwcapHex, info.cpuFeatures)
                .joinToString("\n")
                .ifEmpty { stringResource(R.string.system_value_unknown) },
            wide = true,
        )

        InfoGroup(stringResource(R.string.system_group_engine))
        // ggml's own build line. This is the honest answer to "does the shipped
        // engine contain dot-product kernels?" — it reports compile flags, so a
        // DOTPROD = 0 here holds no matter what the CPU above can do.
        InfoRow(
            label = stringResource(R.string.system_row_build),
            value = info.engineBuildFeatures.ifEmpty { stringResource(R.string.system_value_pending) },
            wide = true,
        )
        InfoRow(
            label = stringResource(R.string.system_row_backends),
            value = info.backends.ifEmpty { stringResource(R.string.system_value_pending) },
            wide = true,
        )

        InfoGroup(stringResource(R.string.system_group_memory))
        InfoRow(
            stringResource(R.string.system_row_ram),
            stringResource(
                R.string.system_ram_value,
                formatMegabytes(info.availableRamMb),
                formatMegabytes(info.totalRamMb),
            ),
        )
        InfoRow(stringResource(R.string.system_row_thermal), info.thermal)
        InfoRow(
            stringResource(R.string.system_row_power_save),
            stringResource(
                if (info.powerSaveMode) R.string.system_value_on else R.string.system_value_off,
            ),
        )

        InfoGroup(stringResource(R.string.system_group_plan))
        InfoRow(
            stringResource(R.string.system_row_threads),
            planValue(
                effective = info.effectiveThreads,
                userChosen = info.threadsUserChosen,
                max = info.plan.maxThreads,
            ),
        )
        InfoRow(
            stringResource(R.string.system_row_context),
            planValue(
                effective = info.effectiveContextTokens,
                userChosen = info.contextUserChosen,
                max = info.plan.maxContextTokens,
                suffix = stringResource(R.string.system_tokens_suffix),
            ),
        )
        InfoRow(stringResource(R.string.system_row_batch), info.plan.batchSize.toString())
        // The point of the panel: when the plan is smaller than the hardware would
        // allow, say which live condition shrank it rather than looking broken.
        InfoRow(
            label = stringResource(R.string.system_row_constraints),
            value = info.plan.constraints
                .joinToString(", ") { it.label }
                .ifEmpty { stringResource(R.string.system_value_none) },
            wide = true,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            modifier = Modifier.padding(top = dimensionResource(R.dimen.space_xs)),
        ) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(info.report()))
                // Android 13+ shows its own copy confirmation; a toast would double it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.system_info_copied, Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(stringResource(R.string.action_copy_system_info))
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.action_refresh_system_info))
            }
        }
    }
}

@Composable
private fun InfoGroup(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = dimensionResource(R.dimen.space_s)),
    )
}

/**
 * One label/value pair. [wide] stacks the value under the label instead of
 * sharing the row, for the long multi-word values (feature lists, backend
 * strings) that would otherwise wrap into a narrow column.
 */
@Composable
private fun InfoRow(label: String, value: String, wide: Boolean = false) {
    if (wide) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
        }
    }
}

/** "4 (Auto) · max 6" — the effective value, where it came from, and the ceiling. */
@Composable
private fun planValue(
    effective: Int,
    userChosen: Boolean,
    max: Int,
    suffix: String? = null,
): String {
    val head = stringResource(
        if (userChosen) R.string.system_chosen_value else R.string.system_auto_value,
        effective,
    )
    val withSuffix = if (suffix != null) "$head $suffix" else head
    return "$withSuffix · " + stringResource(R.string.system_max_suffix, max)
}

/** MB as MB below a gigabyte, GB with one decimal above it. */
private fun formatMegabytes(mb: Long): String =
    if (mb < 1024) {
        "$mb MB"
    } else {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

@Composable
private fun SavedProfilesSection(
    state: SettingsUiState,
    onSwitch: (ModelProfile) -> Unit,
    onDeactivate: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    renamingId?.let { id ->
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text(stringResource(R.string.profile_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(id, renameText); renamingId = null }, enabled = renameText.isNotBlank()) {
                    Text(stringResource(R.string.history_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingId = null }) { Text(stringResource(R.string.model_download_confirm_cancel)) }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        if (state.savedProfiles.isEmpty()) {
            Text(
                stringResource(R.string.profile_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (profile in state.savedProfiles) {
                val isActive = profile.id == state.activeProfileId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                        val typeLabel = stringResource(
                            when (profile.type) {
                                ProviderType.SPACE -> R.string.profile_type_space
                                ProviderType.OPEN_ROUTER -> R.string.profile_type_openrouter
                                ProviderType.CUSTOM -> R.string.profile_type_custom
                            },
                        )
                        Text(
                            "$typeLabel · ${profile.model.substringAfterLast('/').take(30)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (isActive) {
                        Text(
                            stringResource(R.string.profile_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(R.color.brand_cloud),
                        )
                        OutlinedButton(onClick = onDeactivate) {
                            Text(stringResource(R.string.model_use).let { "×" })
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSwitch(profile) },
                            enabled = !state.providerValidating,
                        ) {
                            Text(stringResource(R.string.profile_switch))
                        }
                    }
                    IconButton(onClick = {
                        renameText = profile.name; renamingId = profile.id
                    }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.profile_rename),
                            modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                        )
                    }
                    IconButton(onClick = { onDelete(profile.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.profile_delete),
                            tint = colorResource(R.color.brand_blocked),
                            modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                        )
                    }
                }
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
private fun OpenRouterSection(
    state: SettingsUiState,
    onFetch: (String) -> Unit,
    onSelect: (String, OpenRouterClient.FreeModel) -> Unit,
    onRemoveKey: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        Text(
            stringResource(R.string.openrouter_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.orKeySaved) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            ) {
                Text(
                    stringResource(R.string.openrouter_key_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.brand_local),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onRemoveKey) { Text(stringResource(R.string.openrouter_remove_key)) }
            }
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    if (state.orKeySaved) stringResource(R.string.openrouter_change_key)
                    else stringResource(R.string.openrouter_key_label),
                )
            },
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
                enabled = (apiKey.isNotBlank() || state.orKeySaved) && !state.openRouterLoading,
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
        val activeProfileModel = state.savedProfiles.firstOrNull { it.id == state.activeProfileId }?.model
        for (model in state.openRouterModels) {
            OpenRouterRow(
                model = model,
                active = model.id == activeProfileModel,
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

// ---------------------------------------------------------------------------
// Self-hosted Space section
// ---------------------------------------------------------------------------

@Composable
private fun SpaceSection(
    state: SettingsUiState,
    onConnect: (String, String) -> Unit,
    onLoadModel: (SpaceClient.CatalogModel) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var spaceUrl by remember { mutableStateOf(state.spaceUrl) }
    var spaceToken by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s))) {
        Text(
            stringResource(R.string.space_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.spaceCredentialsSaved) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            ) {
                Text(
                    stringResource(R.string.space_connected_to, state.spaceUrlPreview),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.brand_local),
                    modifier = Modifier.weight(1f),
                )
                if (!state.spaceConnected) {
                    OutlinedButton(
                        onClick = { onConnect(state.spaceUrl, state.spaceToken) },
                        enabled = !state.spaceConnecting,
                    ) { Text(stringResource(R.string.space_connect)) }
                }
                OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.space_disconnect)) }
            }
            if (state.spaceConnecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                        strokeWidth = dimensionResource(R.dimen.stroke_thin),
                    )
                    Text(stringResource(R.string.space_connecting), style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            OutlinedTextField(
                value = spaceUrl,
                onValueChange = { spaceUrl = it },
                label = { Text(stringResource(R.string.space_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = spaceToken,
                onValueChange = { spaceToken = it },
                label = { Text(stringResource(R.string.space_token_label)) },
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
                    onClick = { onConnect(spaceUrl, spaceToken) },
                    enabled = spaceUrl.isNotBlank() && spaceToken.isNotBlank() && !state.spaceConnecting,
                ) {
                    Text(stringResource(R.string.space_connect))
                }
                if (state.spaceConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                        strokeWidth = dimensionResource(R.dimen.stroke_thin),
                    )
                    Text(
                        stringResource(R.string.space_connecting),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        state.spaceError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.brand_blocked))
        }

        if (state.spaceConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
            ) {
                OutlinedButton(onClick = onRefresh, enabled = !state.spaceCatalogLoading) {
                    Text(stringResource(R.string.space_refresh))
                }
                if (state.spaceCatalogLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_progress)),
                        strokeWidth = dimensionResource(R.dimen.stroke_thin),
                    )
                    Text(
                        stringResource(R.string.space_loading_catalog),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            state.spaceLoadProgress?.let { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs))) {
                    if (progress is SpaceClient.LoadEvent.Downloading) {
                        LinearProgressIndicator(
                            progress = { progress.pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val progressText = when (progress) {
                        is SpaceClient.LoadEvent.Downloading ->
                            stringResource(R.string.space_downloading, progress.pct)
                        SpaceClient.LoadEvent.Cached -> stringResource(R.string.space_cached)
                        SpaceClient.LoadEvent.Loading -> stringResource(R.string.space_loading_model)
                        else -> null
                    }
                    progressText?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (state.spaceCatalog.isEmpty() && !state.spaceCatalogLoading) {
                Text(
                    stringResource(R.string.space_catalog_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (model in state.spaceCatalog) {
                SpaceCatalogRow(
                    model = model,
                    enabled = !state.providerValidating && state.spaceLoadProgress == null,
                    onLoad = { onLoadModel(model) },
                )
            }
        }
    }
}

@Composable
private fun SpaceCatalogRow(
    model: SpaceClient.CatalogModel,
    enabled: Boolean,
    onLoad: () -> Unit,
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
                    model.family,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SpaceSuitabilityChip(model.suitability)
                if (model.cached) {
                    Text(
                        stringResource(R.string.space_model_cached),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.brand_local),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.space_size, model.sizeMb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onLoad, enabled = enabled) {
            Text(stringResource(R.string.space_load))
        }
    }
}

@Composable
private fun SpaceSuitabilityChip(suitability: SpaceClient.Suitability) {
    val (labelRes, color) = when (suitability) {
        SpaceClient.Suitability.RECOMMENDED ->
            R.string.suitability_recommended to colorResource(R.color.brand_local)
        SpaceClient.Suitability.HEAVY ->
            R.string.suitability_heavy to colorResource(R.color.brand_cloud)
        SpaceClient.Suitability.INSUFFICIENT ->
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
