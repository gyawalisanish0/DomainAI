# Architecture

Domain AI is a single-module Android app using MVVM, Jetpack Compose, and a small
hand-rolled dependency container. The design centers on one idea: **a single,
auditable policy core decides where every message is answered and enforces every
privacy guarantee on the way out.**

## Layers

```
sg.act.domain
├── DomainApp / AppContainer      Manual DI; builds the repository once.
├── MainActivity                  Compose host + Navigation (chat ⇄ settings).
├── ui/
│   ├── theme/                    ColorScheme built from color resources.
│   ├── chat/                     ChatScreen + ChatViewModel (StateFlow UI state).
│   ├── settings/                 SettingsScreen + SettingsViewModel.
│   └── components/               Reusable, resource-driven composables.
├── data/
│   ├── model/                    Message, Conversation, Role, Route.
│   ├── repository/ChatRepository Single source of truth; orchestrates routing.
│   └── local/                    Encrypted persistence (conversations + config).
├── inference/
│   ├── InferenceEngine           Common contract for local & remote.
│   ├── LocalEngine               On-device (default); GGUF/llama.cpp hook.
│   ├── RemoteEngine              Opt-in OpenAI-compatible client (used for all cloud).
│   ├── PrivacyRouter             The policy core.
│   ├── ModelManager              Lifecycle: download / import / load / unload / benchmark.
│   ├── ModelCatalog              Curated on-device GGUF list with RAM requirements.
│   ├── SpaceClient               Self-hosted Space: /health, /v1/catalog, /v1/admin/load SSE.
│   └── OpenRouterClient          Free OpenRouter model listing.
└── privacy/
    ├── PrivacyState              Pure config (kill switch, consent, redaction).
    ├── PrivacySettings           DataStore-backed persistence of PrivacyState.
    ├── NetworkGuard              The single outbound chokepoint.
    └── PiiRedactor               Pure PII stripping logic.
```

## Data flow for one message

1. `ChatScreen` collects `ChatUiState` from `ChatViewModel`.
2. User taps send. For both local and cloud sends, `ChatRepository.send` appends
   the user message and calls `PrivacyRouter.answer`.
3. `PrivacyRouter`:
   - `useCloud == false` → answer **LOCAL**, always.
   - `useCloud == true` → `NetworkGuard.assertNetworkAllowed` must pass (kill
     switch off **and** consent given) or it falls back to LOCAL with a note.
   - Before any cloud call, the prompt **and history** are redacted; the redacted
     text that was sent is recorded so the UI can show it on the reply.
   - On any cloud error, it falls back to LOCAL — the user is never left without a
     reply, and never silently escalated.
4. The reply (with its `Route` and, for cloud, the exact redacted text that was
   sent) is appended and the conversation is persisted encrypted.

## Why a separate `PrivacyState`

`PrivacyState` is a pure data class with no Android dependencies, so the router
and guard logic that consume it are unit-testable on the JVM. `PrivacySettings`
(DataStore) is the only Android-coupled piece, and it merely persists that state.

## Testability

`PrivacyRouter`, `NetworkGuard`, and `PiiRedactor` are pure Kotlin and covered by
JVM unit tests. Engines are injected behind the `InferenceEngine` interface, so
tests use fakes and never need a model or a network.

## The on-device engine (`:llama` native module)

The real local model runs through a native module:

```
llama/
├── build.gradle.kts                 com.android.library + externalNativeBuild (arm64-v8a)
└── src/main/
    ├── java/sg/act/domain/llama/LLamaAndroid.kt   Kotlin wrapper; all native
    │                                                calls on one dedicated thread
    └── cpp/
        ├── CMakeLists.txt           add_subdirectory(llama.cpp); links llama + log
        ├── llama-android.cpp        JNI bridge (load/context/batch/sampler/decode)
        └── llama.cpp/               vendored llama.cpp source (pinned commit)
```

- `LLamaAndroid` owns the llama.cpp context and exposes `load`/`unload`/`send`
  (a `Flow<String>` of token deltas). The context is single-threaded, so every
  native call is serialized on one executor.
- `LlamaCppBackend` adapts that to `LocalEngine.NativeBackend`, applying each loaded
  model's own embedded chat template (`format_chat`), with a generic `ChatFormat`
  fallback for models that ship none. `ModelManager` owns the lifecycle and hands
  `LocalEngine` a backend provider, so the active model swaps at runtime without
  rebuilding the router.
- The JNI is written against llama.cpp's current C API and avoids the `common`
  helper lib (batches are filled inline), keeping the build to a single small
  `libllama-android.so` plus the ggml backends.

### Streaming

`InferenceEngine.generate` returns `Flow<String>`. `PrivacyRouter` decides
route/redaction synchronously and returns a `StreamingOutcome` carrying the token
flow; `ChatRepository` seeds an empty reply and appends deltas as they arrive, so
the UI streams live off the existing `conversation` StateFlow.

## Cloud providers

All cloud providers are routed through `RemoteEngine`, which speaks
OpenAI-compatible `/v1/chat/completions`. The three provider paths that write into
`RemoteEngine.Config` are:

| Provider | Entry point | Notes |
|----------|-------------|-------|
| **Self-hosted Space** | `SpaceClient` + `SettingsViewModel.connectSpace` / `loadSpaceModel` | Pings `/health`, fetches `/v1/catalog`, streams SSE load progress from `/v1/admin/load`, then calls `validateAndSave` |
| **OpenRouter** | `OpenRouterClient` + `SettingsViewModel.fetchOpenRouterModels` / `selectOpenRouterModel` | Fetches warm free models from the OpenRouter catalog; sets `logsData` per-model |
| **Custom endpoint** | `SettingsViewModel.saveProvider` | Direct form entry; any OpenAI-compatible URL |

`validateAndSave` always does a real round-trip (`ChatRepository.validateProvider`)
before persisting credentials — the config is never saved if the server doesn't
respond correctly. The stored API key is encrypted via `EncryptedSharedPreferences`
and is never read back into the UI.

### SpaceClient SSE flow

```
Android                         Space
  │  POST /v1/admin/load         │
  │ ──────────────────────────►  │  download model (if not cached)
  │ ◄── {"status":"downloading","pct":N} ──
  │ ◄── {"status":"cached","pct":100}  ──  (if already on disk)
  │ ◄── {"status":"loading"}     │  load into llama.cpp context
  │ ◄── {"status":"ready","model":"…"} ─
  │ ◄── data: [DONE]             │
  │                              │
  │  validateAndSave(config)     │  round-trip /v1/chat/completions
  │ ──────────────────────────►  │
  │ ◄── streaming reply          │
  │  persist encrypted config    │
```

`SpaceClient.loadModel` is a `Flow<LoadEvent>` running on `Dispatchers.IO`; the
ViewModel collects it and updates `spaceLoadProgress` in the UI state for each
event. On `Ready`, it extracts the model label and calls `validateAndSave`.
