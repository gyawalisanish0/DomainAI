# Model selection — UI wiring

How the active on-device/cloud model is chosen and displayed, and the invariant
that keeps the three surfaces consistent.

## Surfaces that show the active model

| Surface | Where | Reads |
| --- | --- | --- |
| Chat top-bar subtitle | `ui/chat/ChatScreen.kt` → `selectedModelLabel(state)` | `ChatUiState.modelState` (`State.Loading`/`Ready.modelName`), or the cloud name when `preferCloud` |
| Inference quick-panel checkmark | `ui/chat/ChatScreen.kt` → `ModelChoices` / `ModelPickerRow`, `selected = !preferCloud && model.isActive` | `ChatUiState.installed[].isActive` |
| Settings "In use" / "Use" button | `ui/settings/SettingsScreen.kt` → `InstalledModelRow` | `SettingsUiState.installed[].isActive` |

The two ViewModels assemble their state from the same two `ModelManager` flows:

- `ModelManager.state: StateFlow<State>` — the **live** model lifecycle
  (`NotLoaded` / `Loading` / `Ready` / `Error`). Drives the subtitle / status line.
- `ModelManager.installed: StateFlow<List<InstalledModel>>` — every GGUF on disk,
  each with an `isActive` flag. Drives the checkmark / "In use".

## The invariant

**`isActive` is derived from the live `state`, not from persistence.**
`refreshInstalled()` flags the model whose `fileName` matches the model currently
**loading or loaded** (`ModelManager.activeFileName()` reads `state`), and it is
called the instant `state` becomes `Loading`. Because the subtitle and the
checkmark both ultimately reflect `ModelManager.state`, they cannot disagree —
including during a load.

`ModelStore` (encrypted prefs) still records the last **successfully** loaded model,
but only for auto-load on next launch (`loadActiveModelIfPresent`). It deliberately
does **not** drive the UI's active marker.

## Selecting a model (data flow)

1. User taps a model in the quick-panel or Settings →
   `ChatViewModel.selectLocalModel` / `SettingsViewModel.select` →
   `ModelManager.startSelect(fileName)`.
2. `selectInstalled` → `loadIntoContext(path, displayName, fileName)`:
   - sets `state = Loading(displayName, fileName)` and immediately
     `refreshInstalled()` → the checkmark moves to this model **now**.
   - GPU-offload ladder loads the model; on success
     `state = Ready(displayName, detail, fileName)`.
3. On success, `modelStore.save(...)` persists it (for next launch), then a final
   `refreshInstalled()`.
4. On failure at every offload level, `state = Error`; `activeFileName()` is null →
   no checkmark, and the subtitle shows the error/offline label. Still consistent.

## The bug this prevents

Previously `isActive` was computed from `modelStore.load()?.fileName`, which is only
written **after** a load completes. So for the whole duration of a load (when
"Loading on-device model…" is shown) the subtitle showed the **new** model while the
checkmark still pointed at the **last-saved** one — and they stayed split if the load
failed. Deriving `isActive` from the live `state` removes the split entirely.

## Acquisition (download / import) is a separate process

Getting a model onto the device is its **own** lifecycle, tracked by
`ModelManager.transfer: StateFlow<TransferState>` — distinct from `state` (the
in-memory load):

```
TransferState = Idle | Downloading(modelName, progress) | Importing(modelName) | Failed(modelName, message)
```

- A network **download** or local **import** drives only `transfer`; it does **not**
  set `State.Loading`. So a long download shows real progress
  ("Downloading X — 42%") instead of a stuck "Loading on-device model…", and a
  **failed** download/import surfaces on `transfer` (`Failed`) **without clobbering**
  the model that is actually loaded (`state` is untouched).
- Once the bytes are on disk, `transfer` returns to `Idle` and `activate()` runs the
  in-memory load — which is the only thing that sets `State.Loading → Ready`.
- UI: the chat screen shows a download/import banner from `transfer` and the load
  banner from `state`; Settings shows download progress / importing / a dismissible
  failure from `transfer`, and the model status line from `state`. The
  `DomainApp` notification follows `transfer` (progress) then the load (`state`),
  gated so a plain model switch or the startup load never posts.

So "Loading" now means exactly one thing — loading into memory — and downloading,
importing, and loading are three visibly distinct states.
