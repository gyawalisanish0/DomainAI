package sg.act.domain.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import sg.act.domain.data.repository.ChatRepository
import sg.act.domain.inference.InstalledModel
import sg.act.domain.inference.ModelCatalog
import sg.act.domain.inference.ModelDownloader
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelSpec
import sg.act.domain.inference.OpenRouterClient
import sg.act.domain.inference.RemoteEngine
import sg.act.domain.privacy.DeviceCapabilities
import sg.act.domain.privacy.PrivacyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** A catalog model annotated with whether this device can comfortably run it. */
data class ModelOption(
    val spec: ModelSpec,
    val suitability: DeviceCapabilities.Suitability,
)

data class SettingsUiState(
    val privacy: PrivacyState = PrivacyState(),
    val hasProvider: Boolean = false,
    val modelState: ModelManager.State = ModelManager.State.NotLoaded,
    val downloadProgress: ModelDownloader.Progress? = null,
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
    // OpenRouter free-model picker
    val openRouterModels: List<OpenRouterClient.FreeModel> = emptyList(),
    val openRouterLoading: Boolean = false,
    val openRouterError: String? = null,
    val activeModelId: String? = null,
    // Provider validation (round-trip check before saving)
    val providerValidating: Boolean = false,
    val providerError: String? = null,
)

class SettingsViewModel(
    private val application: Application,
    private val repository: ChatRepository,
    private val modelManager: ModelManager,
    deviceCapabilities: DeviceCapabilities,
    private val openRouter: OpenRouterClient = OpenRouterClient(),
) : ViewModel() {

    private val _ui = MutableStateFlow(
        SettingsUiState(
            hasProvider = repository.hasCloudProvider(),
            totalRamMb = deviceCapabilities.totalRamMb,
            activeModelId = repository.activeCloudModelId(),
            gpuEnabled = modelManager.gpuEnabled(),
            contextTokens = modelManager.contextTokens(),
            contextOptions = modelManager.contextOptions(),
            effectiveContextTokens = modelManager.effectiveContextTokens(),
            catalog = ModelCatalog.models.map {
                ModelOption(it, deviceCapabilities.rate(it.minRamMb))
            },
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        repository.privacyState
            .onEach { _ui.value = _ui.value.copy(privacy = it) }
            .launchIn(viewModelScope)
        modelManager.state
            .onEach { _ui.value = _ui.value.copy(modelState = it) }
            .launchIn(viewModelScope)
        modelManager.downloadProgress
            .onEach { _ui.value = _ui.value.copy(downloadProgress = it) }
            .launchIn(viewModelScope)
        modelManager.installed
            .onEach { _ui.value = _ui.value.copy(installed = it) }
            .launchIn(viewModelScope)
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
        validateAndSave(RemoteEngine.Config(baseUrl.trim(), apiKey.trim(), model.trim()))
    }

    fun clearProvider() {
        repository.clearRemoteConfig()
        _ui.value = _ui.value.copy(
            hasProvider = false,
            activeModelId = null,
            providerError = null,
            openRouterModels = emptyList(),
        )
    }

    /**
     * Validate the config with a real round-trip (confirms key + model routing),
     * and persist it only if it works. The stored key is never read back into the
     * UI — to change it you delete and re-enter.
     */
    private suspend fun validateAndSave(config: RemoteEngine.Config) {
        _ui.value = _ui.value.copy(providerValidating = true, providerError = null)
        val result = repository.validateProvider(config)
        if (result.isSuccess) {
            repository.saveRemoteConfig(config)
            _ui.value = _ui.value.copy(providerValidating = false)
            refreshProvider()
        } else {
            _ui.value = _ui.value.copy(
                providerValidating = false,
                providerError = result.exceptionOrNull()?.message ?: "Couldn't validate the connection.",
            )
        }
    }

    /** Fetch OpenRouter's currently-free chat models into the picker. */
    fun fetchOpenRouterModels(apiKey: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(openRouterLoading = true, openRouterError = null)
        try {
            val models = openRouter.fetchFreeModels(apiKey.trim().ifBlank { null })
            _ui.value = _ui.value.copy(openRouterModels = models, openRouterLoading = false)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                openRouterLoading = false,
                openRouterError = e.message ?: "Could not reach OpenRouter.",
            )
        }
    }

    /** Validate and save a chosen free OpenRouter model as the active cloud provider. */
    fun selectOpenRouterModel(apiKey: String, model: OpenRouterClient.FreeModel) =
        viewModelScope.launch {
            validateAndSave(
                RemoteEngine.Config(
                    baseUrl = OpenRouterClient.BASE_URL,
                    apiKey = apiKey.trim(),
                    model = model.id,
                    logsData = model.logsData,
                ),
            )
        }

    private fun refreshProvider() {
        _ui.value = _ui.value.copy(
            hasProvider = repository.hasCloudProvider(),
            activeModelId = repository.activeCloudModelId(),
        )
    }

    fun downloadModel(spec: ModelSpec) {
        // Runs on the app scope inside ModelManager, so it survives leaving Settings.
        modelManager.startDownload(spec)
    }

    fun importModel(uri: Uri) {
        val name = queryDisplayName(uri) ?: "imported-model.gguf"
        // Open the stream now (cheap); ModelManager reads/closes it on the app scope.
        val input = application.contentResolver.openInputStream(uri) ?: return
        modelManager.startImport(input, name, name)
    }

    fun cancelDownload() = modelManager.cancelDownload()

    fun unloadModel() = viewModelScope.launch { modelManager.unload() }

    /** Toggle GPU offload; reloads the active model so the change takes effect. */
    fun setGpuEnabled(enabled: Boolean) {
        modelManager.setGpuEnabled(enabled)
        _ui.value = _ui.value.copy(gpuEnabled = enabled, benchmark = null)
    }

    /** Set the context length (0 = Auto); reloads the active model to apply it. */
    fun setContextTokens(tokens: Int) {
        modelManager.setContextTokens(tokens)
        _ui.value = _ui.value.copy(
            contextTokens = tokens,
            effectiveContextTokens = modelManager.effectiveContextTokens(),
            benchmark = null,
        )
    }

    /** Run the fixed-prompt speed benchmark on the loaded model. */
    fun runBenchmark() = viewModelScope.launch {
        _ui.value = _ui.value.copy(benchmarkRunning = true, benchmark = null)
        val result = runCatching { modelManager.benchmark() }.getOrNull()
        _ui.value = _ui.value.copy(benchmarkRunning = false, benchmark = result)
    }

    /** Load an already-installed model and make it active. */
    fun selectModel(fileName: String) = modelManager.startSelect(fileName)

    /** Delete an installed model from device storage. */
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(application, repository, modelManager, deviceCapabilities) as T
    }
}
