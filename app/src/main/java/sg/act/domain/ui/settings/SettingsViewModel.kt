package sg.act.domain.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import sg.act.domain.R
import sg.act.domain.data.local.ModelProfileStore
import sg.act.domain.data.repository.ChatRepository
import sg.act.domain.inference.InstalledModel
import sg.act.domain.inference.ModelCatalog
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelProfile
import sg.act.domain.inference.ModelSpec
import sg.act.domain.inference.OpenRouterClient
import sg.act.domain.inference.ProviderType
import sg.act.domain.inference.RemoteEngine
import sg.act.domain.inference.SpaceClient
import sg.act.domain.core.SystemInfo
import sg.act.domain.privacy.DeviceCapabilities
import sg.act.domain.privacy.PrivacyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A catalog model annotated with whether this device can comfortably run it. */
data class ModelOption(
    val spec: ModelSpec,
    val suitability: DeviceCapabilities.Suitability,
)

data class SettingsUiState(
    val privacy: PrivacyState = PrivacyState(),
    val modelState: ModelManager.State = ModelManager.State.NotLoaded,
    val transfer: ModelManager.TransferState = ModelManager.TransferState.Idle,
    val catalog: List<ModelOption> = emptyList(),
    val installed: List<InstalledModel> = emptyList(),
    val totalRamMb: Long = 0,
    // GPU offload preference + speed benchmark
    val gpuEnabled: Boolean = true,
    val benchmarkRunning: Boolean = false,
    val benchmark: ModelManager.BenchmarkResult? = null,
    // Context length (0 = Auto), allowed presets, and the value Auto resolves to.
    val contextTokens: Int = 0,
    val contextOptions: List<Int> = emptyList(),
    val effectiveContextTokens: Int = 0,
    // Generation threads (0 = Auto), allowed presets, and the value Auto resolves to.
    val threadCount: Int = 0,
    val threadOptions: List<Int> = emptyList(),
    val effectiveThreads: Int = 0,
    // Engine overrides (see EngineSettings): the automatic decisions, made visible
    // and reversible.
    val reusePromptCache: Boolean = true,
    val strictAffinity: Boolean = false,
    val highPriority: Boolean = false,
    // OpenRouter free-model picker
    val openRouterModels: List<OpenRouterClient.FreeModel> = emptyList(),
    val openRouterLoading: Boolean = false,
    val openRouterError: String? = null,
    // Self-hosted Space section
    val spaceCredentialsSaved: Boolean = false,
    val spaceUrlPreview: String = "",
    val spaceUrl: String = "",
    val spaceToken: String = "",
    val spaceConnecting: Boolean = false,
    val spaceConnected: Boolean = false,
    val spaceCatalog: List<SpaceClient.CatalogModel> = emptyList(),
    val spaceCatalogLoading: Boolean = false,
    val spaceLoadProgress: SpaceClient.LoadEvent? = null,
    val spaceError: String? = null,
    // OpenRouter key persistence
    val orKeySaved: Boolean = false,
    // Saved inference profiles
    val savedProfiles: List<ModelProfile> = emptyList(),
    val activeProfileId: String? = null,
    // Provider validation (round-trip check before saving)
    val providerValidating: Boolean = false,
    val providerError: String? = null,
    /** Settings -> System info. Null until the first sample completes. */
    val systemInfo: SystemInfo? = null,
)

class SettingsViewModel(
    private val application: Application,
    private val repository: ChatRepository,
    private val modelManager: ModelManager,
    private val deviceCapabilities: DeviceCapabilities,
    private val profileStore: ModelProfileStore,
    private val openRouter: OpenRouterClient = OpenRouterClient(),
    private val spaceClient: SpaceClient = SpaceClient(),
) : ViewModel() {

    private val _ui = MutableStateFlow(
        SettingsUiState(
            totalRamMb = deviceCapabilities.totalRamMb,
            gpuEnabled = modelManager.gpuEnabled(),
            contextTokens = modelManager.contextTokens(),
            contextOptions = modelManager.contextOptions(),
            effectiveContextTokens = modelManager.effectiveContextTokens(),
            threadCount = modelManager.threadCount(),
            threadOptions = modelManager.threadOptions(),
            effectiveThreads = modelManager.effectiveThreads(),
            reusePromptCache = modelManager.reusePromptCache(),
            strictAffinity = modelManager.strictAffinity(),
            highPriority = modelManager.highPriority(),
            catalog = ModelCatalog.models.map {
                ModelOption(it, deviceCapabilities.rate(it.minRamMb))
            },
            spaceCredentialsSaved = profileStore.spaceUrl != null,
            spaceUrlPreview = profileStore.spaceUrl?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/') ?: "",
            spaceUrl = profileStore.spaceUrl ?: "",
            spaceToken = profileStore.spaceToken ?: "",
            orKeySaved = profileStore.openRouterKey != null,
            savedProfiles = profileStore.profiles.value,
            activeProfileId = profileStore.activeProfileId.value,
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        repository.privacyState
            .onEach { _ui.value = _ui.value.copy(privacy = it) }
            .launchIn(viewModelScope)
        modelManager.state
            .onEach {
                _ui.value = _ui.value.copy(modelState = it)
                // A finished load may have landed on different numbers than the
                // panel last sampled (the ladder steps GPU layers down, and free
                // memory moves), so re-sample when the model state settles.
                refreshSystemInfo()
            }
            .launchIn(viewModelScope)
        modelManager.transfer
            .onEach { _ui.value = _ui.value.copy(transfer = it) }
            .launchIn(viewModelScope)
        modelManager.installed
            .onEach { _ui.value = _ui.value.copy(installed = it) }
            .launchIn(viewModelScope)
        profileStore.profiles
            .onEach { _ui.value = _ui.value.copy(savedProfiles = it) }
            .launchIn(viewModelScope)
        profileStore.activeProfileId
            .onEach { _ui.value = _ui.value.copy(activeProfileId = it) }
            .launchIn(viewModelScope)
        // The engine's build-feature and backend lines are only populated once the
        // native library has initialized, which otherwise waits for a model load —
        // so a device with no model installed would show blanks in the one panel
        // meant to explain why.
        refreshSystemInfo()
    }

    /**
     * Re-sample [SystemInfo]. Cheap (a few system reads and a pure plan), and
     * worth doing on every visit: thermal status and free memory are exactly the
     * values a user opens this panel to check.
     */
    fun refreshSystemInfo() = viewModelScope.launch(Dispatchers.Default) {
        // Only queue work on the native run loop when the engine hasn't come up
        // yet: that thread also runs generation, so an unconditional hop would
        // make this refresh wait out a reply in flight. A failure to initialize is
        // itself reportable — leave the fields empty rather than taking the VM down.
        if (modelManager.engineBuildFeatures().isEmpty()) {
            runCatching { modelManager.ensureEngineInitialized() }
        }
        val info = SystemInfo.collect(deviceCapabilities, modelManager)
        // `update` rather than a copy-assign: this runs off the main thread, where
        // a read-modify-write would race the collectors above.
        _ui.update {
            it.copy(
                systemInfo = info,
                // The Auto labels and the selectable presets come from the same
                // plan, so they follow a phone that has heated up or freed memory
                // instead of quoting process-start values all session.
                contextOptions = modelManager.contextOptions(),
                threadOptions = modelManager.threadOptions(),
                effectiveContextTokens = info.effectiveContextTokens,
                effectiveThreads = info.effectiveThreads,
            )
        }
    }

    fun setKillSwitch(enabled: Boolean) = viewModelScope.launch {
        repository.privacySettings.setKillSwitch(enabled)
    }

    fun setConsent(granted: Boolean) = viewModelScope.launch {
        repository.privacySettings.setCloudConsent(granted)
    }

    fun setRedact(enabled: Boolean) = viewModelScope.launch {
        repository.privacySettings.setRedactBeforeCloud(enabled)
    }

    fun setAllowDataLogging(allowed: Boolean) = viewModelScope.launch {
        repository.privacySettings.setAllowDataLoggingModels(allowed)
    }

    fun setCrashReporting(enabled: Boolean) = viewModelScope.launch {
        repository.privacySettings.setCrashReporting(enabled)
    }

    fun saveProvider(baseUrl: String, apiKey: String, model: String) = viewModelScope.launch {
        val config = RemoteEngine.Config(baseUrl.trim(), apiKey.trim(), model.trim())
        validateAndSave(config, ProviderType.CUSTOM, ModelProfile.autoName(ProviderType.CUSTOM, config.model, config.baseUrl))
    }

    fun deactivateProfile() {
        if (_ui.value.activeProfileId == null) return
        profileStore.setActiveProfileId(null)
        repository.setPreferCloud(false)
        _ui.value = _ui.value.copy(providerError = null)
    }

    fun switchProfile(profile: ModelProfile) = viewModelScope.launch {
        repository.saveRemoteConfig(profile.toRemoteConfig())
        profileStore.setActiveProfileId(profile.id)
        repository.setPreferCloud(true)
    }

    fun deleteProfile(id: String) {
        if (_ui.value.activeProfileId == id) {
            repository.setPreferCloud(false)
        }
        profileStore.deleteProfile(id)
    }

    fun renameProfile(id: String, name: String) {
        profileStore.renameProfile(id, name)
    }

    /**
     * Validate the config with a real round-trip, create/update a named profile, and
     * persist only on success. The stored key is never read back into the UI.
     */
    private suspend fun validateAndSave(config: RemoteEngine.Config, type: ProviderType, name: String) {
        _ui.value = _ui.value.copy(providerValidating = true, providerError = null)
        val result = repository.validateProvider(config)
        if (result.isSuccess) {
            persistProfile(config, type, name)
            _ui.value = _ui.value.copy(providerValidating = false)
        } else {
            _ui.value = _ui.value.copy(
                providerValidating = false,
                providerError = result.exceptionOrNull()?.message
                    ?: application.getString(R.string.provider_validate_failed),
            )
        }
    }

    /** Save a profile directly without a validation round-trip (Space SSE already confirmed ready). */
    private suspend fun saveProfileDirect(config: RemoteEngine.Config, type: ProviderType, name: String) {
        persistProfile(config, type, name)
    }

    private suspend fun persistProfile(config: RemoteEngine.Config, type: ProviderType, name: String) {
        repository.saveRemoteConfig(config)
        val profile = ModelProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = type,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            logsData = config.logsData,
        )
        profileStore.upsertProfile(profile)
        profileStore.setActiveProfileId(profile.id)
        repository.setPreferCloud(true)
    }

    /** Fetch OpenRouter's currently-free chat models into the picker; persists the key. */
    fun fetchOpenRouterModels(apiKey: String) = viewModelScope.launch {
        val trimmedKey = apiKey.trim()
        _ui.value = _ui.value.copy(openRouterLoading = true, openRouterError = null)
        try {
            val models = openRouter.fetchFreeModels(trimmedKey.ifBlank { null })
            if (trimmedKey.isNotBlank()) {
                profileStore.saveOpenRouterKey(trimmedKey)
                _ui.value = _ui.value.copy(orKeySaved = true)
            }
            _ui.value = _ui.value.copy(openRouterModels = models, openRouterLoading = false)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                openRouterLoading = false,
                openRouterError = e.message ?: application.getString(R.string.openrouter_unreachable),
            )
        }
    }

    fun removeOpenRouterKey() {
        profileStore.clearOpenRouterKey()
        _ui.value = _ui.value.copy(orKeySaved = false, openRouterModels = emptyList())
    }

    /** Validate and save a chosen free OpenRouter model as a named profile. */
    fun selectOpenRouterModel(apiKey: String, model: OpenRouterClient.FreeModel) =
        viewModelScope.launch {
            validateAndSave(
                RemoteEngine.Config(
                    baseUrl = OpenRouterClient.BASE_URL,
                    apiKey = apiKey.trim(),
                    model = model.id,
                    logsData = model.logsData,
                ),
                type = ProviderType.OPEN_ROUTER,
                name = ModelProfile.autoName(ProviderType.OPEN_ROUTER, model.id, OpenRouterClient.BASE_URL),
            )
        }

    // -----------------------------------------------------------------------
    // Self-hosted Space
    // -----------------------------------------------------------------------

    /** Ping the Space /health endpoint; on success persists credentials and fetches the catalog. */
    fun connectSpace(url: String, token: String) = viewModelScope.launch {
        val trimUrl = url.trim(); val trimToken = token.trim()
        _ui.value = _ui.value.copy(spaceConnecting = true, spaceError = null)
        val health = spaceClient.fetchHealth(trimUrl, trimToken)
        if (health.reachable) {
            profileStore.saveSpaceCredentials(trimUrl, trimToken)
            val preview = trimUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
            val loadingNote = if (health.loadingInProgress)
                application.getString(R.string.space_loading_in_progress)
            else
                health.loadError?.let { application.getString(R.string.space_prev_load_error, it) }
            _ui.value = _ui.value.copy(
                spaceCredentialsSaved = true,
                spaceUrlPreview = preview,
                spaceUrl = trimUrl,
                spaceToken = trimToken,
                spaceConnecting = false,
                spaceConnected = true,
                spaceError = loadingNote,
            )
            loadSpaceCatalog()
        } else {
            _ui.value = _ui.value.copy(
                spaceConnecting = false,
                spaceError = application.getString(R.string.space_unreachable),
            )
        }
    }

    fun disconnectSpace() {
        profileStore.clearSpaceCredentials()
        _ui.value = _ui.value.copy(
            spaceCredentialsSaved = false,
            spaceUrlPreview = "",
            spaceUrl = "",
            spaceToken = "",
            spaceConnected = false,
            spaceCatalog = emptyList(),
            spaceLoadProgress = null,
            spaceError = null,
        )
    }

    /** (Re-)fetch the catalog from the connected Space. */
    fun refreshSpaceCatalog() = viewModelScope.launch { loadSpaceCatalog() }

    private suspend fun loadSpaceCatalog() {
        _ui.value = _ui.value.copy(spaceCatalogLoading = true, spaceError = null)
        try {
            val catalog = spaceClient.fetchCatalog(_ui.value.spaceUrl, _ui.value.spaceToken)
            _ui.value = _ui.value.copy(spaceCatalog = catalog, spaceCatalogLoading = false)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                spaceCatalogLoading = false,
                spaceError = e.message ?: application.getString(R.string.space_unreachable),
            )
        }
    }

    /**
     * Tell the Space to load [model]. Streams SSE progress into [spaceLoadProgress];
     * on success validates the Space as the active cloud provider.
     */
    fun loadSpaceModel(model: SpaceClient.CatalogModel) = viewModelScope.launch {
        _ui.value = _ui.value.copy(spaceError = null)
        val url = _ui.value.spaceUrl
        val token = _ui.value.spaceToken
        var readyModel: String? = null

        spaceClient.loadModel(url, token, model.repoId, model.filename).collect { event ->
            when (event) {
                is SpaceClient.LoadEvent.Ready -> {
                    readyModel = event.model
                    _ui.value = _ui.value.copy(spaceLoadProgress = null)
                }
                is SpaceClient.LoadEvent.Error -> {
                    _ui.value = _ui.value.copy(
                        spaceLoadProgress = null,
                        spaceError = event.message,
                    )
                }
                else -> _ui.value = _ui.value.copy(spaceLoadProgress = event)
            }
        }

        // The SSE "ready" event already confirms the model loaded — skip the completion
        // round-trip that validateAndSave() would do (first inference after load is slow
        // and often hits the timeout, giving a misleading "Couldn't validate" error).
        readyModel?.let { label ->
            saveProfileDirect(
                RemoteEngine.Config(
                    baseUrl = "$url/v1",
                    apiKey = token,
                    model = label,
                    logsData = false,
                ),
                type = ProviderType.SPACE,
                name = ModelProfile.autoName(ProviderType.SPACE, label, "$url/v1"),
            )
        }
    }

    // -----------------------------------------------------------------------

    fun downloadModel(spec: ModelSpec) {
        modelManager.startDownload(spec)
    }

    fun importModel(uri: Uri) {
        val name = queryDisplayName(uri) ?: "imported-model.gguf"
        val input = application.contentResolver.openInputStream(uri) ?: return
        modelManager.startImport(input, name, name)
    }

    fun cancelDownload() = modelManager.cancelDownload()

    fun dismissTransfer() = modelManager.clearTransfer()

    fun unloadModel() = viewModelScope.launch { modelManager.unload() }

    fun setGpuEnabled(enabled: Boolean) {
        modelManager.setGpuEnabled(enabled)
        _ui.value = _ui.value.copy(gpuEnabled = enabled, benchmark = null)
    }

    fun setContextTokens(tokens: Int) {
        modelManager.setContextTokens(tokens)
        _ui.value = _ui.value.copy(
            contextTokens = tokens,
            effectiveContextTokens = modelManager.effectiveContextTokens(),
            benchmark = null,
        )
    }

    fun setReusePromptCache(enabled: Boolean) {
        modelManager.setReusePromptCache(enabled)
        _ui.value = _ui.value.copy(reusePromptCache = enabled)
    }

    // Both of these rebuild the threadpool, which only happens at load — hence the
    // reload inside ModelManager, and the cleared benchmark: the previous number
    // no longer describes how the engine is running.
    fun setStrictAffinity(enabled: Boolean) {
        modelManager.setStrictAffinity(enabled)
        _ui.value = _ui.value.copy(strictAffinity = enabled, benchmark = null)
    }

    fun setHighPriority(enabled: Boolean) {
        modelManager.setHighPriority(enabled)
        _ui.value = _ui.value.copy(highPriority = enabled, benchmark = null)
    }

    fun setThreadCount(count: Int) {
        modelManager.setThreadCount(count)
        _ui.value = _ui.value.copy(
            threadCount = count,
            effectiveThreads = modelManager.effectiveThreads(),
            benchmark = null,
        )
    }

    fun runBenchmark() = viewModelScope.launch {
        _ui.value = _ui.value.copy(benchmarkRunning = true, benchmark = null)
        val result = runCatching { modelManager.benchmark() }.getOrNull()
        _ui.value = _ui.value.copy(benchmarkRunning = false, benchmark = result)
    }

    fun selectModel(fileName: String) = modelManager.startSelect(fileName)

    fun deleteModel(fileName: String) = modelManager.startDelete(fileName)

    private fun queryDisplayName(uri: Uri): String? =
        application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    class Factory(
        private val application: Application,
        private val repository: ChatRepository,
        private val modelManager: ModelManager,
        private val deviceCapabilities: DeviceCapabilities,
        private val profileStore: ModelProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(application, repository, modelManager, deviceCapabilities, profileStore) as T
    }
}
