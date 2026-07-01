package sg.act.domain

import android.app.Application
import sg.act.domain.core.CrashReporting
import sg.act.domain.core.ForegroundWork
import sg.act.domain.data.local.AcceptanceStore
import sg.act.domain.data.local.ConversationStore
import sg.act.domain.data.local.ModelProfileStore
import sg.act.domain.data.local.ModelStorage
import sg.act.domain.data.local.ModelStore
import sg.act.domain.data.local.RemoteConfigStore
import sg.act.domain.data.local.SelectionStore
import sg.act.domain.inference.ModelProfile
import sg.act.domain.inference.ProviderType
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
        deviceRecommendedBatchSize = deviceCapabilities.recommendedBatchSize(),
        contextSettings = ContextSettings(app),
        threadSettings = ThreadSettings(app),
        gpuGuard = GpuGuard(app),
        nativeLibDir = app.applicationInfo.nativeLibraryDir,
        sdkInt = android.os.Build.VERSION.SDK_INT,
    )

    private val remoteConfigStore = RemoteConfigStore(app)
    private val selectionStore = SelectionStore(app)

    val repository: ChatRepository = ChatRepository(
        context = app,
        privacySettings = PrivacySettings(app),
        conversationStore = ConversationStore(app),
        remoteConfigStore = remoteConfigStore,
        selectionStore = selectionStore,
        localEngine = LocalEngine(backendProvider = modelManager::activeBackend),
        contextTokens = modelManager::effectiveContextTokens,
        localModelLoaded = { modelManager.activeBackend() != null },
    )

    val modelProfileStore = ModelProfileStore(app)

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

    init {
        // Swipe-away from recents cancels in-flight background work. Generation on the
        // UI scope dies with the Activity; this covers a download on the app scope.
        ForegroundWork.onStopRequested = { modelManager.cancelDownload() }

        // One-time migration: import a legacy single RemoteConfig into the profile store.
        if (modelProfileStore.profiles.value.isEmpty()) {
            remoteConfigStore.load()?.let { legacy ->
                val type = if (legacy.baseUrl.contains("openrouter.ai")) ProviderType.OPEN_ROUTER else ProviderType.CUSTOM
                val profile = ModelProfile(
                    id = java.util.UUID.randomUUID().toString(),
                    name = ModelProfile.autoName(type, legacy.model, legacy.baseUrl),
                    type = type,
                    baseUrl = legacy.baseUrl,
                    apiKey = legacy.apiKey,
                    model = legacy.model,
                    logsData = legacy.logsData,
                )
                modelProfileStore.upsertProfile(profile)
                if (selectionStore.preferCloud()) modelProfileStore.setActiveProfileId(profile.id)
            }
        }

        // Re-load the previously active on-device model, off the main thread.
        appScope.launch { modelManager.loadActiveModelIfPresent() }

        // Crash reporting: inert unless Firebase is configured, and gated on the
        // user's opt-in (off by default). Mirror the live setting into Crashlytics.
        CrashReporting.init(app)
        appScope.launch {
            repository.privacyState.collect { CrashReporting.setEnabled(it.crashReportingEnabled) }
        }

        // Drive the download/import foreground service off the transfer lifecycle,
        // then the in-memory load that follows it. The service spans the whole
        // acquire-then-load sequence (one begin, one end), so the process keeps the
        // download alive in the background. Gated on `transferring`/`loading` so a
        // plain model switch or the startup load (state-only, no transfer) never posts.
        appScope.launch {
            var transferring = false // a user download/import is in flight
            var loading = false      // the post-transfer in-memory load has begun
            combine(modelManager.state, modelManager.transfer) { s, t -> s to t }
                .collect { (state, transfer) ->
                    when (transfer) {
                        is ModelManager.TransferState.Downloading -> {
                            val text = app.getString(R.string.notif_downloading, transfer.modelName)
                            if (!transferring) {
                                transferring = true
                                ForegroundWork.begin(app, text)
                            }
                            val pct = ((transfer.progress?.fraction ?: 0f) * 100).toInt()
                            ForegroundWork.update(app, text, pct)
                        }
                        is ModelManager.TransferState.Importing -> {
                            if (!transferring) {
                                transferring = true
                                ForegroundWork.begin(app, app.getString(R.string.model_initializing))
                            }
                        }
                        is ModelManager.TransferState.Failed -> {
                            if (transferring) ForegroundWork.end(app)
                            ForegroundWork.failed(app, app.getString(R.string.notif_failed))
                            transferring = false; loading = false
                        }
                        ModelManager.TransferState.Idle -> when {
                            !transferring -> Unit // not our transfer; ignore startup/switch loads
                            state is ModelManager.State.Loading -> {
                                loading = true
                                ForegroundWork.update(app, app.getString(R.string.model_initializing))
                            }
                            state is ModelManager.State.Ready && loading -> {
                                ForegroundWork.end(app)
                                ForegroundWork.complete(app, app.getString(R.string.notif_complete, state.modelName))
                                transferring = false; loading = false
                            }
                            state is ModelManager.State.Error && loading -> {
                                ForegroundWork.end(app)
                                ForegroundWork.failed(app, app.getString(R.string.notif_failed))
                                transferring = false; loading = false
                            }
                            else -> Unit
                        }
                    }
                }
        }
    }
}
