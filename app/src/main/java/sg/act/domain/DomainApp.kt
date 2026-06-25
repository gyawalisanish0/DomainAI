package sg.act.domain

import android.app.Application
import sg.act.domain.core.CrashReporting
import sg.act.domain.core.DownloadNotifier
import sg.act.domain.data.local.AcceptanceStore
import sg.act.domain.data.local.ConversationStore
import sg.act.domain.data.local.ModelStorage
import sg.act.domain.data.local.ModelStore
import sg.act.domain.data.local.RemoteConfigStore
import sg.act.domain.data.local.SelectionStore
import sg.act.domain.data.repository.ChatRepository
import sg.act.domain.inference.ContextSettings
import sg.act.domain.inference.GpuGuard
import sg.act.domain.inference.LocalEngine
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ThreadSettings
import sg.act.domain.privacy.DeviceCapabilities
import sg.act.domain.privacy.PrivacySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Application entry point. Hosts a tiny hand-rolled dependency container — the
 * app is small enough that a DI framework would be overkill.
 */
class DomainApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: DomainApp) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val deviceCapabilities = DeviceCapabilities(app)

    val modelManager = ModelManager(
        context = app,
        modelStore = ModelStore(app),
        modelStorage = ModelStorage(app),
        scope = appScope,
        deviceRecommendedContext = deviceCapabilities.recommendedContextTokens(),
        deviceMaxContext = deviceCapabilities.maxAllowedContextTokens(),
        deviceAutoThreads = deviceCapabilities.recommendedThreads,
        deviceMaxThreads = deviceCapabilities.maxThreads,
        coresBySpeed = deviceCapabilities.coresBySpeed,
        contextSettings = ContextSettings(app),
        threadSettings = ThreadSettings(app),
        gpuGuard = GpuGuard(app),
        nativeLibDir = app.applicationInfo.nativeLibraryDir,
        sdkInt = android.os.Build.VERSION.SDK_INT,
    )

    val repository: ChatRepository = ChatRepository(
        context = app,
        privacySettings = PrivacySettings(app),
        conversationStore = ConversationStore(app),
        remoteConfigStore = RemoteConfigStore(app),
        selectionStore = SelectionStore(app),
        localEngine = LocalEngine(backendProvider = modelManager::activeBackend),
        contextTokens = modelManager::effectiveContextTokens,
        localModelLoaded = { modelManager.activeBackend() != null },
    )

    val acceptanceStore = AcceptanceStore(app)

    /**
     * Record first-launch acceptance of the Terms + Privacy Policy. Per the chosen
     * flow this also grants the optional cloud permissions (cloud consent +
     * data-logging models); both remain revocable in Settings. The network kill
     * switch stays ON so the app is still fully offline until the user opts out.
     */
    fun acceptTerms() {
        acceptanceStore.setAccepted()
        appScope.launch {
            repository.privacySettings.setCloudConsent(true)
            repository.privacySettings.setAllowDataLoggingModels(true)
        }
    }

    private val downloadNotifier = DownloadNotifier(app)

    init {
        // Re-load the previously active on-device model, off the main thread.
        appScope.launch { modelManager.loadActiveModelIfPresent() }

        // Crash reporting: inert unless Firebase is configured, and gated on the
        // user's opt-in (off by default). Mirror the live setting into Crashlytics.
        CrashReporting.init(app)
        appScope.launch {
            repository.privacyState.collect { CrashReporting.setEnabled(it.crashReportingEnabled) }
        }

        // Drive the download/import notification off the transfer lifecycle, then
        // the in-memory load that follows it. Gated on `transferring`/`loading` so a
        // plain model switch or the startup load (state-only, no transfer) never posts.
        appScope.launch {
            var transferring = false // a user download/import is in flight
            var loading = false      // the post-transfer in-memory load has begun
            combine(modelManager.state, modelManager.transfer) { s, t -> s to t }
                .collect { (state, transfer) ->
                    when (transfer) {
                        is ModelManager.TransferState.Downloading -> {
                            transferring = true
                            downloadNotifier.progress(transfer.modelName, transfer.progress?.fraction ?: 0f)
                        }
                        is ModelManager.TransferState.Importing -> {
                            transferring = true
                            downloadNotifier.indeterminate(app.getString(R.string.model_initializing))
                        }
                        is ModelManager.TransferState.Failed -> {
                            downloadNotifier.failed(); transferring = false; loading = false
                        }
                        ModelManager.TransferState.Idle -> when {
                            !transferring -> Unit // not our transfer; ignore startup/switch loads
                            state is ModelManager.State.Loading -> {
                                loading = true
                                downloadNotifier.indeterminate(app.getString(R.string.model_initializing))
                            }
                            state is ModelManager.State.Ready && loading -> {
                                downloadNotifier.complete(state.modelName); transferring = false; loading = false
                            }
                            state is ModelManager.State.Error && loading -> {
                                downloadNotifier.failed(); transferring = false; loading = false
                            }
                            else -> Unit
                        }
                    }
                }
        }
    }
}
