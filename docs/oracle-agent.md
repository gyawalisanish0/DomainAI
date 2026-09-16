# Oracle — on-device dev agent (concept / design doc)

> **Status:** concept — not built. Captured for future development as a standalone
> tool.
>
> **Naming:** *Oracle* is the **developer tooling agent** described here. It is
> distinct from the chat app in this repo, which is now **Domain AI**
> (`sg.act.domain`). "Oracle" was the app's former name; here it is reused as the
> name of the dev-loop agent.

## 1. Why

Domain AI's build and release path runs **entirely on GitHub Actions**, but the
verification path is still manual: the debug APK is installed by hand, the app is
exercised by hand, and logs are gathered by hand (currently via the in-app
*Settings → Share app logs* export). That manual stretch is the bottleneck
regardless of what hardware the developer has, because the things worth verifying —
real on-device inference, the GPU fallback, thermal behaviour — only happen on a
physical ARM device. Oracle's goal is to **close that loop and automate it**, so the
cycle becomes hands-off:

```
push fix → CI builds APK → Oracle installs it → Oracle drives the scenario →
Oracle captures full logcat → Oracle pushes the log to the repo → a workflow
digests it and comments on the PR → the digest reaches the maintainer/agent →
next fix → …
```

Once paired, the only unavoidable human touch is whatever Android security forces
(see §7).

## 2. What Oracle is

A **native Android app** (separate from Domain AI) that uses **wireless debugging
(ADB)** against its *own* device to:

- gather **full-system logcat** (as the `shell` user, no root),
- **silently install** new CI builds (`pm install -r`),
- **drive** Domain AI deterministically (`am broadcast` to a debug automation
  receiver),
- **upload** captured logs to the GitHub repo,
- **poll** CI for new builds.

It is conceptually a focused, purpose-built **LADB** (the open-source local-ADB
app) tailored to this project's loop.

## 3. How it works (the proven technique)

A normal app cannot link a generic "adb library" that performs the Android 11+
pairing handshake. The reliable approach (as used by LADB) is:

1. **Bundle a prebuilt arm64 Android `adb` binary** shipped inside the APK as
   `lib/arm64-v8a/libadb.so`. Files under the APK's `lib/` are extracted to
   `nativeLibraryDir`, which is **executable** — app data dirs are not (W^X on
   Android 10+). Oracle `exec`s this binary as a subprocess.
2. **In-app pairing UX**: the user opens *Settings → Developer options → Wireless
   debugging → Pair device with pairing code* and enters the 6-digit code + pairing
   port into Oracle. Oracle runs:
   - `adb pair 127.0.0.1:<pairPort>` (with the code), then
   - `adb connect 127.0.0.1:<connectPort>`.
   ADB keys are stored under the app's `filesDir` (`$HOME/.android`).
3. Once connected, Oracle runs `adb shell logcat`, `pm install`, `am broadcast`,
   etc. as the **shell** user.

### The rotating-port problem
The wireless-debugging **connect port changes** whenever the toggle is cycled
(pairing is more stable). Oracle should **auto-discover** it via
`adb mdns services` (look for `_adb-tls-connect._tcp`) and fall back to a manual
port field. Re-`connect` automatically when a session drops.

## 4. Architecture

- **Separate app module `:agent`** in this repo → builds `agent-debug.apk`. Kept
  apart from Domain AI because it must install/reinstall and drive Domain AI; it
  can't reliably do that to itself if merged in.
- **Screens**
  - *Pair / Connect* — code + ports, mDNS auto-discovery, saved pairing.
  - *Console* — live `logcat` stream with filters (tag/pid/level), copy/share.
  - *Actions* — Install latest CI build · Run scenario · Upload log · Configure.
- **Reuses the existing stack**: OkHttp for GitHub (list runs, download artifact,
  push log), EncryptedSharedPreferences for the PAT + saved pairing.
- **Foreground service + wake-lock** so HyperOS/aggressive OEM task-killers don't
  kill it mid-loop.

## 5. Domain AI side — the automation receiver (keystone)

To reproduce bugs deterministically (instead of brittle `input tap` coordinates),
Domain AI gains a **debug-only** `BroadcastReceiver` (registered in `src/debug`
only, like the existing FileProvider):

- Action: `sg.act.domain.debug.AUTOMATION`
- Oracle sends e.g.
  `am broadcast -a sg.act.domain.debug.AUTOMATION --es action load_model --es model gemma-3-4b-it-Q4_K_M.gguf`
- The receiver calls existing code (`ModelManager.selectInstalled(...)`, a chat
  send, a download) and logs each step (already routed to logcat).

Initial command set:

| Command | Effect (logged) |
| --- | --- |
| `load_model <file>` | load a model; log success + winning `n_gpu_layers`, or failure reason |
| `send <prompt>` | run one chat turn; log reply/errors |
| `download <id>` | trigger a catalog download |
| `dump_state` | print RAM, active model, GPU-disabled flag, registered backends |

The receiver must be **debug-only** and ideally guarded by a signature permission
so only a co-signed Oracle build can invoke it.

## 6. GitHub side — `device-logs.yml`

- Trigger: push to a `device-logs` branch (path `runs/**`).
- Parse the newest log: extract the `Loading model …` block, the `n_gpu_layers`
  ladder outcome, ERROR/WARN lines, final model state.
- Write `runs/latest-summary.md` and **post the digest as a comment on the open
  PR**, so a watching maintainer/agent is notified automatically.

## 7. Security & constraints

- **PAT**: one fine-grained token, this repo only — *Contents: R/W* (push to
  `device-logs`), *Actions: read* (list runs + download artifacts). Stored in
  Oracle's EncryptedSharedPreferences; never committed.
- **adb keys** live in Oracle's private `filesDir`.
- **adb binary** is a vendored blob — pin its **SHA-256** and document provenance
  (same discipline as the vendored llama.cpp source). Alternatively have CI fetch
  it from a pinned, checksummed URL instead of committing it.
- Oracle is **debug tooling** — never published to an app store.
- **What ADB cannot bypass**: even as `shell`, a first-time `pm install` of a new
  package may prompt; updates to an already-installed signed package are silent.
  Pairing is required once per "Wireless debugging" reset.

## 8. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| adb binary provenance/auditability | pinned SHA-256 + documented source, or CI-fetched |
| OEM task-killer (HyperOS) kills the agent | foreground service + wake-lock; reconnect loop |
| Connect port rotates | mDNS auto-discovery; manual fallback; auto-reconnect |
| Pairing friction | one-time per WD reset; persist keys; clear in-app guidance |
| Second app to maintain | CI builds `agent-debug.apk` alongside Domain AI |
| Android version differences in WD/exec | test matrix; the `libadb.so` exec trick is the compatible path |

## 9. Open decisions (to settle when development starts)

1. **App placement** — separate `:agent` module (recommended) vs a debug section
   inside Domain AI.
2. **adb binary sourcing** — vendor pinned prebuilt vs CI-fetched checksummed.
3. **Run trigger** — auto-run scenario on connect vs manual "Run" button.

## 10. Suggested phasing

- **Phase 1 — the logcat tool.** `:agent` app + bundled adb + pair/connect + live
  logcat capture + Share/Upload to the repo. Immediately useful on its own.
- **Phase 2 — close the loop.** Silent install of CI builds + the Domain AI
  automation receiver + `device-logs.yml` digest → PR comment + CI building the
  agent APK.

## 11. Prior art

- **LADB** — open-source local-ADB-over-wireless-debugging app; the reference for
  the bundled-`adb`/`libadb.so` exec technique and the in-app pairing flow.
- **dadb** (mobile.dev) — a Kotlin ADB client library; useful for the connect/shell
  path, but the Android 11 *pairing* handshake is the part that pushes us toward
  bundling the real `adb` binary.
