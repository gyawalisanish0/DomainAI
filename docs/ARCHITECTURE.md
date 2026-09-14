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
│   ├── model/                    Message, Conversation, Role, Route + rewind rules.
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

## Redoing a turn (regenerate / edit & resend)

Both actions are the same move: **rewind, then send again**. `ConversationRewind`
trims the conversation back to just before a user turn and hands back that turn's
prompt; `ChatRepository` then calls the ordinary `send` with it. There is no second
generation path, so a redone turn is routed, redacted, history-budgeted and
persisted exactly like a fresh message — including re-deciding local-vs-cloud under
whatever settings are in force now.

Trimming is pure (`Conversation` in, `Conversation` out — no Android, no
coroutines), which is what makes the fiddly parts JVM-testable: clamping
`summarizedCount` so the rolling summary can't claim messages that no longer exist,
dropping the summary when the chat is emptied, and releasing a chat's title back to
`DEFAULT_TITLE` so a reworded opening question renames it.

The view model owns a single generation slot: `send`, `regenerate` and
`editAndResend` all claim it through one guard, and the UI only offers the actions
between generations — regenerate on the newest reply only, since regenerating an
older one would silently discard everything said after it.

## Why a separate `PrivacyState`

`PrivacyState` is a pure data class with no Android dependencies, so the router
and guard logic that consume it are unit-testable on the JVM. `PrivacySettings`
(DataStore) is the only Android-coupled piece, and it merely persists that state.

## Testability

`PrivacyRouter`, `NetworkGuard`, `PiiRedactor` and the `ConversationRewind` rules
are pure Kotlin and covered by JVM unit tests. Engines are injected behind the
`InferenceEngine` interface, so tests use fakes and never need a model or a
network.

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
  helper lib (batches are filled inline), keeping our own `libllama-android.so`
  small.

### CPU kernel selection

ggml's fast integer kernels are compile-time gated on ARM feature macros
(`__ARM_FEATURE_DOTPROD`, `__ARM_FEATURE_MATMUL_INT8`, …) that the compiler only
defines from `-march`. A cross-compile that names no target therefore silently
produces a binary with *none* of them — which is what the build did until v1.11.

Committing the whole library to one `-march` would fix that at the cost of every
device below the chosen baseline, so the build instead sets `GGML_CPU_ALL_VARIANTS`:
ggml compiles its CPU backend once per feature tier (it ships an Android-specific
list, `android_armv8.0_1` … `android_armv9.2_2`) and each variant scores itself
against the running CPU so the best supported one wins at startup.

The consequence is packaging. `GGML_CPU_ALL_VARIANTS` requires `GGML_BACKEND_DL`,
which requires `BUILD_SHARED_LIBS` — so the native payload is no longer one static
library but `libllama`/`libggml`/`libggml-base` plus a `libggml-cpu-<tier>.so` per
variant, and the GPU backends become dlopen-able modules too rather than being
statically linked in.

Nothing is registered until those modules are loaded, and the registry's default
search paths (the executable's directory, the process CWD) resolve to `/system/bin`
and `/` on Android — where it finds nothing. `backend_init` therefore calls
`ggml_backend_load_all_from_path` with the app's `nativeLibraryDir`, plumbed through
from `DomainApp` via `ModelManager` → `LLamaAndroid.configure`. **If that path is
wrong or missing, no backend registers and no model can load at all** — it is the
one load-bearing step in this arrangement.

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
