# CI, signing & crash reporting — building Domain AI entirely on GitHub

You can build, sign, and ship Domain AI without a local Android toolchain — no SDK,
NDK or CMake install required. Everything runs in GitHub Actions, so a browser is
enough, on whatever machine you happen to have. This guide walks through it end to
end.

## What the two workflows do

| Workflow | Trigger | Output | Signing | Firebase |
| --- | --- | --- | --- | --- |
| **`ci.yml`** | every push / PR to `main` | `app-debug` APK artifact | your release key *(if secrets set)*, else default debug key | enabled *(if secret set)* |
| **`release.yml`** | pushing a tag matching `v*` | `app-release` signed APK artifact | your release key **(required)** | enabled *(if secret set)* |

Both workflows now consume the **same** `GOOGLE_SERVICES_JSON_BASE64` secret, so
Firebase/Crashlytics is wired into **both variants** (debug *and* release). The
debug build installs as `sg.act.domain.debug` (side-by-side with release); Gradle
auto-mirrors your `google-services.json` to the `.debug` package at build time, so
crashes from both variants report to the same Firebase project.

Every assemble step also prints the signing certificate **SHA-1 / SHA-256** to the
build log (look for the `[signing]` lines), so you can read them without a local
keystore or `keytool` install.

---

## One-time setup (all in the browser)

You'll create a keystore and base64-encode a few files. Any Linux/macOS terminal with
`keytool`, `base64` and `openssl` will do. If you'd rather not install a JDK locally,
a free browser shell works just as well — **GitHub Codespaces** (open this repo →
`<> Code` → *Codespaces* → *Create codespace*) or **Google Cloud Shell**
(<https://shell.cloud.google.com>); both ship all three preinstalled.

### Step 1 — Create your release keystore (once)

In the browser shell:

```bash
keytool -genkeypair -v \
  -keystore oracle-release.keystore \
  -alias oracle \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

It prompts for a keystore password and your name/org (any values are fine). Use the
**same** value when it asks for the key password (press Enter to reuse the store
password). **Keep this file and its passwords safe** — losing them means you can
never update the app under the same signature.

### Step 2 — Base64-encode the keystore

```bash
base64 -w0 oracle-release.keystore
```

Copy the single long line it prints — that's the value for `ORACLE_KEYSTORE_BASE64`.

> Download the `.keystore` file itself for safekeeping too (in Codespaces: right-click
> the file → *Download*). It is **never** committed — `*.keystore` is gitignored.

### Step 3 — (Optional) Get your Firebase config

Skip this if you don't want crash reporting yet — the app builds fine without it.

1. Create a Firebase project at <https://console.firebase.google.com>.
2. *Add app → Android*, package name **`sg.act.domain`**, and download
   `google-services.json`.
3. Base64-encode it in the browser shell:

   ```bash
   base64 -w0 google-services.json
   ```

   Copy the output — that's `GOOGLE_SERVICES_JSON_BASE64`.

You do **not** need to register a separate Firebase app for `sg.act.domain.debug`
or add any SHA fingerprints — Crashlytics doesn't require SHA registration. (Only
add the printed SHA-1/SHA-256 in Firebase later if you adopt a service that needs
it, e.g. Firebase Auth, App Check, or Dynamic Links.)

### Step 4 — Add the GitHub secrets

In the repo: **Settings → Secrets and variables → Actions → New repository secret**.
Add:

| Secret | Required | Value |
| --- | --- | --- |
| `ORACLE_KEYSTORE_BASE64` | release; recommended for debug | output of Step 2 |
| `ORACLE_KEYSTORE_PASSWORD` | with the above | your keystore password |
| `ORACLE_KEY_ALIAS` | with the above | `oracle` |
| `ORACLE_KEY_PASSWORD` | with the above | your key password |
| `GOOGLE_SERVICES_JSON_BASE64` | optional | output of Step 3 |

That's it — no files are committed; the workflows decode these at build time.

---

## Building & downloading APKs

### Debug build (fast, for testing on your phone)

Push any commit to `main` (or open a PR). The **CI** workflow runs, and when it's
green:

1. Open the run under the repo's **Actions** tab.
2. Scroll to **Artifacts** → download **`app-debug`**.
3. Unzip on your phone and install `app-debug.apk` (allow *Install unknown apps* for
   your browser/files app). It installs as **Domain AI (debug)** — `sg.act.domain.debug`
   — alongside any release build.

If you set the keystore secrets, this debug APK is signed with your release key, so
it updates cleanly and matches your Firebase SHA.

### Release build (signed, for distribution)

Create and push a version tag. From the browser shell (or the GitHub web UI →
*Releases → Draft a new release → choose a tag → Publish*):

```bash
git tag v1.01
git push origin v1.01
```

The **Release** workflow runs `assembleRelease` with your keystore and uploads the
signed **`app-release`** artifact. Download it from the run's **Artifacts** the same
way. The signing SHA is printed in the *Print signing SHA fingerprints* step.

---

## Reading your signing SHA from CI

Open any CI or Release run → the **Print signing SHA fingerprints** step. You'll see:

```
[signing] configured keystore (alias=oracle)
  SHA-1:   AA:BB:...
  SHA-256: 11:22:...
```

(When no keystore secret is set, CI prints the default debug keystore's SHA instead.)

---

## Local signed builds

Create `keystore.properties` in the repo root (gitignored):

```properties
storeFile=/absolute/path/to/oracle-release.keystore
storePassword=...
keyAlias=oracle
keyPassword=...
```

Then `./gradlew assembleRelease`. Without it (and without the env vars), the release
build is produced **unsigned** and CI/debug are unaffected.

---

## Firebase Crashlytics — privacy

Collection is **off by default** (manifest meta-data + runtime gate). It only turns
on when the user enables *Send crash reports* in Settings, and only crash/error
reports are sent — no analytics or usage tracking. Without a Firebase config, no
telemetry code path is ever active. The Gradle plugins activate automatically only
when `app/google-services.json` is present at build time.
