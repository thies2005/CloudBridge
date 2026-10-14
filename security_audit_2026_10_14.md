# CloudBridge Security Audit Report & Multi-Agent Looping Remediation Plan

- **Target Application**: CloudBridge (`de.schuelken.cloudbridge`)
- **Evaluated Commit**: `ece9275441e448ec520a7cbc336c59bfe643b22c`
- **Audit Date**: `2026-10-14`
- **Audit Mode & Profile**: `@security-audit` Normal Mode (`full_audit` / `standard` profile)
- **Schema Validation Status**: `coverage-ledger.json` (`PASS: 9 coverage units valid`) | `findings.json` (`PASS: 10 findings valid`)

## Table of Contents

1. Executive Summary & Security Posture
2. Baseline Reconciliation: Prior `AUDIT_AND_REMEDIATION_PLAN.md` vs. Current Commit
3. Architecture, Trust Boundaries & Attack-Surface Coverage Ledger
4. Confirmed Security Findings (`CB-SEC-001` to `CB-SEC-008`)
5. Needs-Validation Findings (`CB-VAL-001` to `CB-VAL-002`)
6. Remaining Reliability & Crash Backlog (`CB-REL-001` to `CB-REL-005`)
7. Multi-Agent Looping Remediation Plan for Weaker Subagents
   - 7.1 Loop Architecture & Finite State Machine
   - 7.2 Operating Rules & Guardrails for Weaker Subagents
   - 7.3 Shared Loop State Schema (`loop-state.json`)
   - 7.4 Copy-Pasteable Subagent Prompt Templates (Orchestrator, Worker, Verifier, Critic)
   - 7.5 Automated Loop Controller Script (`scripts/run-remediation-loop.sh`)
   - 7.6 Ordered Micro-Task Specifications (Waves 1–5, `TASK-W1-01` to `TASK-W5-05`)

## 1. Executive Summary & Security Posture

CloudBridge (`de.schuelken.cloudbridge`) is an Android cloud storage manager and background file synchronization client built around a native Go shared library (`librclone.so`) compiled from `thies2005/rclone` (v1.74.3 fork). At commit `ece9275441e448ec520a7cbc336c59bfe643b22c`, several historical issues inherited from the upstream Round Sync codebase have already been remediated (such as removing the tracked `roundsync.keystore` file from the working tree, setting `android:allowBackup="false"`, removing the unauthenticated APK updater `UpdateUserchoiceReceiver`, pinning GitHub Actions and `rCloneRef` to 40-character commit SHAs, and forcing `Rclone.serve()` onto `127.0.0.1` when credentials are empty).

However, a source-grounded audit of the current tree identified **8 confirmed vulnerabilities** (**2 High**, **4 Medium**, **2 Low**) and **2 external validation items**, plus **5 unfixed reliability/crash defects** from the engineering backlog that affect worker and provider stability.

| ID | Fingerprint | Severity | Status | Component / File | Summary |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **CB-SEC-001** | `sharing-activity-path-traversal-and-file-uri-staging` | **HIGH** | Confirmed | `SharingActivity.java:77, 242, 265` | Exported `SharingActivity` copies incoming URIs in `onCreate` using unsanitized `DISPLAY_NAME` into `getExternalCacheDir()`, enabling arbitrary file overwrite across `/storage/emulated/0/` (via `MANAGE_EXTERNAL_STORAGE`) and staging of internal `/data/data` files via `file://` URIs. |
| **CB-SEC-002** | `ci-translations-workflow-command-injection` | **HIGH** | Confirmed | `.github/workflows/translations.yml:55, 66` | `on: pull_request` workflow interpolates `${{ steps.google-translate.outputs.text }}` and `${{ needs.string-change-verification.outputs.files }}` directly inside `sh` `run:` blocks, enabling arbitrary command execution on the CI runner from untrusted PRs. |
| **CB-SEC-003** | `shortcut-service-activity-unprotected-export` | **MEDIUM** | Confirmed | `AndroidManifest.xml:59`, `ShortcutServiceActivity.kt:22` | Headless `ShortcutServiceActivity` is exported (`android:exported="true"`) with no permission check, allowing any local app to silently trigger any configured sync task (`START_TASK`), including destructive one-way syncs. |
| **CB-SEC-004** | `credential-storage-plaintext-and-obscured-at-rest` | **MEDIUM** | Confirmed | `Rclone.java:571, 1568`, `SafAccessProvider.java:48` | `ADR-001` remains unimplemented (`Status: PROPOSED`): `rclone.conf` uses reversible keyless `--obscure`, `decryptConfig()` overwrites encrypted configs with plaintext on disk, and `SharedPreferences` stores `safDavPass` and `pref_key_proxy_password` in cleartext. |
| **CB-SEC-005** | `verbose-logging-writes-sensitive-headers-and-errors-to-external-storage` | **MEDIUM** | Confirmed | `RcloneRcd.java:136, 153`, `Log2File.java:24` | Enabling "Use logs" (`pref_key_logs`) passes `--dump headers -vvv` to `rclone rcd` and writes `rcd.log`, `serve.log`, and `log.txt` (including raw HTTP `Authorization` headers) to `getExternalFilesDir("logs")`. |
| **CB-SEC-006** | `download-and-open-external-cache-path-traversal-and-exposure` | **MEDIUM** | Confirmed | `FileExplorerFragment.java:1853-1861` | Opening/previewing a remote file downloads it into `getExternalCacheDirs()[0]` using an unsanitized `fileItem.getName()` path and leaves plaintext files in external cache storage. |
| **CB-SEC-007** | `local-loopback-services-weak-or-unauthenticated-access` | **LOW** | Confirmed | `FileExplorerFragment.java:1956`, `SafDAVServer.java:97` | `StreamTask` starts `StreamingService` on `127.0.0.1:8080` with no credentials or `--baseurl` capability token (readable by any local app with `INTERNET`), and `SafDAVServer` uses non-constant-time `String.equals` for Basic Auth. |
| **CB-SEC-008** | `rclone-remote-name-flag-injection-in-cli-invocations` | **LOW** | Confirmed | `Rclone.java:668, 1326, 1409`, `DynamicRemoteConfigFragment.kt:513` | Remote names are not validated against leading hyphens (`-`) on creation or config import, and `Rclone.java` omits `"--"` before positional remote arguments passed to `librclone.so`. |
| **CB-VAL-001** | `signing-key-rotation-and-ci-secret-verification` | **MEDIUM** (Est.) | Needs Validation | `.github/workflows/android.yml:80`, `app/build.gradle:76` | Cannot verify offline whether `secrets.CB_KEYSTORE_BASE64` rotated away from the historically leaked `roundsync.keystore`; `app/build.gradle:76` silently falls back to `signingConfigs.debug` if the secret is missing. |
| **CB-VAL-002** | `rclone-fork-commit-and-transport-integrity` | **LOW** (Est.) | Needs Validation | `gradle.properties:33`, `rclone/build.gradle:248` | Custom Go fork (`thies2005/rclone` @ `94f543c9...`) is fetched at build time and not vendored offline; `rclone/build.gradle` does not assert `git rev-parse HEAD == RCLONE_REF` after checkout. |

## 2. Baseline Reconciliation: Prior `AUDIT_AND_REMEDIATION_PLAN.md` vs. Current Commit

The repository root contains an older planning document (`AUDIT_AND_REMEDIATION_PLAN.md`). Before planning subagent tasks, it is essential to distinguish which items from that document have **already been fixed** in commit `ece9275441e448ec520a7cbc336c59bfe643b22c` versus which items remain **unfixed** or **newly discovered** in the current audit:

| Historical ID (`AUDIT_AND_REMEDIATION_PLAN.md`) | Historical Description | Status in Commit `ece927544...` | Verification Evidence in Current Tree |
| :--- | :--- | :--- | :--- |
| **RC-1** | Hardcoded release keystore (`roundsync.keystore`) in repo | **PARTIALLY FIXED** (`CB-VAL-001`) | File removed from working tree; `app/build.gradle:14-24` reads `keystore.properties` or `CB_*` env vars, but line 76 still silently falls back to `signingConfigs.debug` in CI and key rotation in GitHub Secrets requires verification. |
| **RC-2** | `android:allowBackup="true"` in `AndroidManifest.xml` | **FIXED** | `app/src/main/AndroidManifest.xml:23` sets `android:allowBackup="false"`. |
| **RC-3** | Plaintext / obscured credentials at rest (`ADR-001`) | **UNFIXED** (`CB-SEC-004`) | `docs/ADR-001-credential-storage-at-rest.md` exists (`Status: PROPOSED`), but no `CredentialStore` or `EncryptedSharedPreferences` code exists; `Rclone.decryptConfig()` (`Rclone.java:1568`) still overwrites encrypted configs with plaintext on disk. |
| **RC-4** | `TriggerServiceReceiver` and `ShortcutServiceActivity` exported without permission | **HALF FIXED** (`CB-SEC-003`) | `TriggerServiceReceiver` (`AndroidManifest.xml:199`) is now `android:exported="false"`, **but** `ShortcutServiceActivity` (`AndroidManifest.xml:59`) is **still** `android:exported="true"` with no permission! |
| **RC-5** | `Rclone.serve` binds `:port` (`0.0.0.0`) with empty credentials | **PARTIALLY FIXED** (`CB-SEC-007`) | `Rclone.java:723-725` now forces `127.0.0.1` when `allowRemoteAccess` lacks credentials, **but** `StreamTask` (`FileExplorerFragment.java:1956`) still serves media on `127.0.0.1:8080` with no capability token (`baseUrl = null`). |
| **RC-8 / RC-9** | Unpinned `rCloneRef=master` and mutable GitHub Action tags | **FIXED** (`CB-VAL-002`) | `gradle.properties:34` pins `rCloneRef=94f543c9d4df4f7a29c87c9b76abf7bb341b5d33` and `.github/workflows/*.yml` pin action SHAs, though `translations.yml` still has shell injection (`CB-SEC-002`). |
| **RC-21 / RC-27** | Unauthenticated `UpdateUserchoiceReceiver` APK installer & duplicate `FileProvider` | **FIXED** | `UpdateUserchoiceReceiver.kt` was deleted and `AndroidManifest.xml:201` has a single non-exported `androidx.core.content.FileProvider`. |
| **RC-14 / RC-15 / RC-16 / RC-18 / RC-26** | Crash bugs in `MainActivity`, `EphemeralWorker`, `SyncWorker`, `ThumbnailsLoadingService`, `VirtualContentProvider` | **UNFIXED** (`CB-REL-001`..`005`) | All 5 crash/lifecycle bugs remain present in the current source tree and are included in Wave 5 of the subagent remediation plan. |

## 3. Architecture, Trust Boundaries & Attack-Surface Coverage Ledger

All 9 attack-surface units in `coverage-ledger.json` were audited and validated via `validate-coverage-ledger.js`:

| Coverage Unit (`surface :: boundary :: subsystem`) | Attack Class | Status | Finding Fingerprints |
| :--- | :--- | :--- | :--- |
| `android-ipc :: documents-provider-and-broadcast-receivers :: app/VirtualContentProvider-and-TriggerServiceReceiver` | `provider-permission-and-broadcast-spoofing` | `covered` | None (`MANAGE_DOCUMENTS` and `exported="false"` verified) |
| `android-ipc :: external-intent-to-sharing-activity :: app/SharingActivity` | `path-traversal-and-arbitrary-uri-staging` | `candidate` | `sharing-activity-path-traversal-and-file-uri-staging` |
| `android-ipc :: external-intent-to-shortcut-service-activity :: app/ShortcutServiceActivity` | `unprotected-exported-component-task-trigger` | `candidate` | `shortcut-service-activity-unprotected-export` |
| `ci-cd-pipeline :: pull-request-to-github-actions-runner :: github-workflows/translations` | `workflow-expression-command-injection` | `candidate` | `ci-translations-workflow-command-injection` |
| `ci-cd-pipeline :: release-build-and-native-dependency-fetch :: github-workflows/android-and-gradle` | `signing-key-and-supply-chain-integrity` | `candidate` | `rclone-fork-commit-and-transport-integrity`, `signing-key-rotation-and-ci-secret-verification` |
| `local-data-storage :: app-logging-and-preview-cache-to-external-storage :: app/Log2File-RcloneRcd-FileExplorerFragment` | `sensitive-log-and-cache-exposure-on-external-storage` | `candidate` | `download-and-open-external-cache-path-traversal-and-exposure`, `verbose-logging-writes-sensitive-headers-and-errors-to-external-storage` |
| `local-data-storage :: app-storage-to-local-filesystem :: app/Rclone-and-SafAccessProvider` | `plaintext-and-obscured-credentials-at-rest` | `candidate` | `credential-storage-plaintext-and-obscured-at-rest` |
| `local-network-daemons :: loopback-and-lan-http-webdav-rcd-listeners :: app/StreamingService-ThumbnailsLoadingService-RcloneRcd-SafDAVServer` | `unauthenticated-loopback-daemon-and-timing-side-channel` | `candidate` | `local-loopback-services-weak-or-unauthenticated-access` |
| `native-process-execution :: java-to-librclone-cli-arguments :: app/Rclone-and-RemoteConfig` | `cli-argument-and-flag-injection` | `candidate` | `rclone-remote-name-flag-injection-in-cli-invocations` |

## 4. Confirmed Security Findings (`CB-SEC-001` to `CB-SEC-008`)

### CB-SEC-001: Exported `SharingActivity` Unsanitized `DISPLAY_NAME` Path Traversal and Arbitrary URI Staging on Create
- **Fingerprint**: `sharing-activity-path-traversal-and-file-uri-staging`
- **Severity**: **HIGH** (Likelihood: High | Impact: High | Confidence: High)
- **Vulnerability Class**: Path Traversal & Confused Deputy File Access (`CWE-22`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java:76-83, 234-283` and `app/src/main/AndroidManifest.xml:174-189`
- **Source Trace**:
  1. `entrypoint` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java:77`: `SharingActivity.onCreate()` is exported (`android:exported="true"`) and immediately executes `copyFile(intent)` or `copyFiles(intent)` upon receiving `Intent.ACTION_SEND` or `Intent.ACTION_SEND_MULTIPLE` before any user interaction.
  2. `propagation` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java:265`: `CopyFile.resolveName(Uri uri)` queries the caller-supplied `Uri` for `OpenableColumns.DISPLAY_NAME` (lines 267–274) or falls back to `uri.getPathSegments()` (line 280) and returns the raw string without stripping path separators (`/`) or parent directory references (`..`).
  3. `propagation` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java:242`: `CopyFile.doInBackground()` constructs `File outFile = new File(cacheDir, fileName)` where `cacheDir = getExternalCacheDir()`, without checking canonical path containment or blocking `file://` URIs pointing to CloudBridge's own internal `/data/data/de.schuelken.cloudbridge/` directory.
  4. `sink` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java:244`: `new FileOutputStream(outFile)` overwrites the resolved path with bytes read from `getContentResolver().openInputStream(uri)`.
- **Root Cause & Impact**:
  - **Arbitrary External Storage Overwrite (Confused Deputy)**: Because CloudBridge requests and holds `MANAGE_EXTERNAL_STORAGE` (`OnboardingActivity.kt`), `new File(getExternalCacheDir(), "../../../../Download/important.pdf")` resolves to `/storage/emulated/0/Download/important.pdf`. Any unprivileged local app with zero storage permissions can send an `ACTION_SEND` Intent backed by its own `ContentProvider` returning `DISPLAY_NAME = "../../../../<target>"` to overwrite arbitrary files on shared external storage immediately in `onCreate()`.
  - **Internal File Staging (`file://` URI)**: Passing `EXTRA_STREAM = Uri.parse("file:///data/data/de.schuelken.cloudbridge/files/rclone.conf")` causes `CopyFile` to copy CloudBridge's private `rclone.conf` into `getExternalCacheDir()/rclone.conf` on external storage and queue it in `uploadList`.
- **Target-Neutral Reproduction**:
  1. From a local test app, send an explicit `Intent(Intent.ACTION_SEND)` to `de.schuelken.cloudbridge/ca.pkay.rcloneexplorer.Activities.SharingActivity` with `type = "text/plain"` and `EXTRA_STREAM` pointing to a `ContentProvider` whose `query()` returns `OpenableColumns.DISPLAY_NAME = "../../../../Download/poc_test.txt"`.
  2. Observe `SharingActivity.onCreate()` immediately invoking `CopyFile.doInBackground()` and writing the stream contents to `/storage/emulated/0/Download/poc_test.txt` before the user selects any remote.
- **Minimal Remediation**:
  - Stage incoming shared files in an internal cache subdirectory (`new File(getCacheDir(), "share_staging")`) rather than `getExternalCacheDir()`.
  - Sanitize `resolveName()` so it strips all directory components (`new File(rawName).getName()`), rejects `.`, `..`, and control characters, and verify `outFile.getCanonicalPath().startsWith(stagingDir.getCanonicalPath() + File.separator)`.
  - Reject any `file://` URI whose canonical path resolves inside `getApplicationInfo().dataDir`.

### CB-SEC-002: Untrusted Pull Request Command Injection in Translations GitHub Actions Workflow
- **Fingerprint**: `ci-translations-workflow-command-injection`
- **Severity**: **HIGH** (Likelihood: High | Impact: High | Confidence: High)
- **Vulnerability Class**: CI/CD Expression & Shell Command Injection (`CWE-78`)
- **Affected Location**: `.github/workflows/translations.yml:49-69` and `scripts/generateFilelist.sh:17-20`
- **Source Trace**:
  1. `entrypoint` — `.github/workflows/translations.yml:5`: Workflow triggers on `pull_request` events (`on: pull_request`).
  2. `propagation` — `scripts/generateFilelist.sh:20`: Extracts added `<string>` lines from `git diff` on `values-*/strings.xml` and strips only double quotes (`tr -d '"'`), leaving single quotes `'`, `$(...)`, and backticks intact.
  3. `propagation` — `.github/workflows/translations.yml:55`: `TRANSLATIONS=$(./scripts/generateFilelist.sh ${{ github.event.pull_request.commits }} ${{needs.string-change-verification.outputs.files}})` expands workflow outputs unquoted in `sh`.
  4. `sink` — `.github/workflows/translations.yml:66-67`:
     ```yaml
     echo "Translations are: '${{ steps.google-translate.outputs.text }}'"
     echo "${{ steps.google-translate.outputs.text }}" | sed 's/ ; /
/g' > translated_texts.txt
     ```
- **Root Cause & Impact**: GitHub Actions evaluates `${{ ... }}` template expressions before handing the script to `sh`. Any `$(command)` or backtick expression preserved through translation (or any double quote introduced by the translation output) is executed as a shell command on the `ubuntu-latest` runner.
- **Minimal Remediation**: Pass `${{ steps.google-translate.outputs.text }}`, `${{ github.event.pull_request.commits }}`, and `${{ needs.string-change-verification.outputs.files }}` through step `env:` variables and write them using `printf '%s
' "$TRANSLATED_TEXT"`.

### CB-SEC-003: Exported Headless `ShortcutServiceActivity` Allows Any Local App to Trigger Arbitrary Sync Tasks
- **Fingerprint**: `shortcut-service-activity-unprotected-export`
- **Severity**: **MEDIUM** (Likelihood: High | Impact: Medium | Confidence: High)
- **Vulnerability Class**: Exposed Android Component / Unauthorized IPC Task Trigger (`CWE-926`)
- **Affected Location**: `app/src/main/AndroidManifest.xml:59` and `app/src/main/java/ca/pkay/rcloneexplorer/Activities/ShortcutServiceActivity.kt:16-35`
- **Source Trace**:
  1. `entrypoint` — `app/src/main/AndroidManifest.xml:59`: `ShortcutServiceActivity` is declared with `android:exported="true"`, `android:theme="@style/HiddenWindowShortcutSyncStartTheme"`, and no `android:permission`.
  2. `propagation` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/ShortcutServiceActivity.kt:22-25`: `onCreate()` calls `moveTaskToBack(true)` and reads `val id = intent.extras?.getLong(EXTRA_TASK_ID)` when `intent.action == TASK_SYNC_ACTION` (`"START_TASK"`).
  3. `sink` — `app/src/main/java/ca/pkay/rcloneexplorer/Activities/ShortcutServiceActivity.kt:26`: `SyncManager(this.baseContext).queue(id)` immediately starts `SyncWorker` for task `id` and finishes.
- **Root Cause & Impact**: Although `TriggerServiceReceiver` was previously made non-exported (`AndroidManifest.xml:199`), `ShortcutServiceActivity` was left exported without a permission guard. Notably, `AppShortcutsHelper.java:50, 183` creates shortcuts targeting `MainActivity`, so `ShortcutServiceActivity` does not even need to be exported to third-party apps. Any local app can iterate small `long` task IDs (`1L..20L`) to silently trigger destructive `SYNC_LOCAL_TO_REMOTE` or `SYNC_REMOTE_TO_LOCAL` jobs.
- **Minimal Remediation**: Change `android:exported="true"` to `android:exported="false"` on `ShortcutServiceActivity` in `app/src/main/AndroidManifest.xml:59`.

### CB-SEC-004: Cloud Credentials and Local Service Passwords Stored Plaintext or Reversibly Obscured at Rest
- **Fingerprint**: `credential-storage-plaintext-and-obscured-at-rest`
- **Severity**: **MEDIUM** (Likelihood: Low | Impact: High | Confidence: High)
- **Vulnerability Class**: Cleartext / Weakly Protected Credentials at Rest (`CWE-256`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java:189, 571, 582, 1568-1581`, `app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java:176`, `app/src/main/java/ca/pkay/rcloneexplorer/util/InternxtReauth.kt:68`, and `safdav/src/main/java/io/github/x0b/safdav/SafAccessProvider.java:37-51`
- **Source Trace**:
  1. `entrypoint` — `Rclone.configCreate` (`Rclone.java:571`), `Rclone.decryptConfig` (`Rclone.java:1532`), and `SafAccessProvider.initAuth` (`SafAccessProvider.java:36`).
  2. `propagation` — `Rclone.decryptConfig` (`Rclone.java:1568-1572`) deletes the encrypted `rclone.conf` and recreates an unencrypted `rclone.conf` containing the output of `rclone config show`.
  3. `sink` — `Rclone.java:1573` writes plaintext config lines to `/data/data/de.schuelken.cloudbridge/files/rclone.conf`, while `SafAccessProvider.java:48` and `Rclone.java:189` store `safDavPass` and `pref_key_proxy_password` in plaintext default `SharedPreferences`.
- **Root Cause & Impact**: `docs/ADR-001-credential-storage-at-rest.md` specifies an Android Keystore-backed `CredentialStore` (`Status: PROPOSED`), but no implementation exists in the codebase yet. Worse, if a user imports an rclone-encrypted `rclone.conf` and unlocks it via `decryptConfig()`, CloudBridge permanently overwrites the encrypted file on disk with plaintext!
- **Minimal Remediation**: Implement `CredentialStore` backed by Android Keystore (`EncryptedSharedPreferences`) for `safDavPass` and `pref_key_proxy_password`, and protect `rclone.conf` via a Keystore-wrapped `RCLONE_CONFIG_PASS` passed in `getRcloneEnv()` (or encrypt/decrypt without permanently destroying config encryption in `decryptConfig`).

### CB-SEC-005: Verbose Logging Writes Full HTTP Authorization Headers and Rclone Logs to Shared External Storage
- **Fingerprint**: `verbose-logging-writes-sensitive-headers-and-errors-to-external-storage`
- **Severity**: **MEDIUM** (Likelihood: Medium | Impact: High | Confidence: High)
- **Vulnerability Class**: Sensitive Information Exposure in External Log Files (`CWE-532`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java:136, 150-155`, `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java:768-772`, and `app/src/main/java/ca/pkay/rcloneexplorer/Log2File.java:24-28`
- **Source Trace**:
  1. `entrypoint` — `RcloneRcd.startRcd()` (`RcloneRcd.java:150`) checks `pref.getBoolean(context.getString(R.string.pref_key_logs), false)`.
  2. `propagation` — `RcloneRcd.java:152-154` adds `"--log-file", logFile, "--dump", "headers", "-vvv"` where `logFile` is `context.getExternalFilesDir("logs").getAbsolutePath() + "/rcd.log"`.
  3. `sink` — `librclone.so` writes full HTTP request/response headers (including `Authorization: Bearer ...` OAuth tokens and Basic auth headers) to `/storage/emulated/0/Android/data/de.schuelken.cloudbridge/files/logs/rcd.log`, alongside `serve.log` (`Rclone.java:769`) and `log.txt` (`Log2File.java:24`).
- **Root Cause & Impact**: Storing logs in `getExternalFilesDir("logs")` exposes them outside the internal `/data/data` sandbox to any app holding `MANAGE_EXTERNAL_STORAGE` (or legacy storage permissions / ADB), and `--dump headers` explicitly logs active cloud bearer tokens.
- **Minimal Remediation**: Remove `"--dump", "headers"` from `RcloneRcd.java:153` and change `RcloneRcd.java:136`, `Rclone.java:769`, and `Log2File.java:24` to write logs to `new File(context.getFilesDir(), "logs")`.

### CB-SEC-006: `DownloadAndOpen` Stages Cloud Files in Shared External Cache Without Filename Sanitization
- **Fingerprint**: `download-and-open-external-cache-path-traversal-and-exposure`
- **Severity**: **MEDIUM** (Likelihood: Medium | Impact: Medium | Confidence: High)
- **Vulnerability Class**: Insecure Temporary File Storage & Path Manipulation (`CWE-379`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java:1853-1877`
- **Source Trace**:
  1. `entrypoint` — `FileExplorerFragment.onFileClicked` (`FileExplorerFragment.java:1186`) launches `DownloadAndOpen`.
  2. `propagation` — `FileExplorerFragment.java:1853-1859` selects `ContextCompat.getExternalCacheDirs(context)[0]` and constructs `fileLocation = saveLocation + "/" + fileItem.getName()`.
  3. `sink` — `FileExplorerFragment.java:1861` calls `rclone.downloadFile(remote, fileItem, saveLocation)` and exposes `new File(fileLocation)` via `FileProvider.getUriForFile`.
- **Root Cause & Impact**: Files opened from encrypted (`crypt`) or private cloud remotes are written in cleartext to external storage cache (`/storage/emulated/0/Android/data/de.schuelken.cloudbridge/cache/`) without filename sanitization or cleanup, even though `app/src/main/res/xml/file_provider_paths.xml` already supports internal `<cache-path name="cache" path="."/>`.
- **Minimal Remediation**: Use `new File(context.getCacheDir(), "preview")` in `DownloadAndOpen.doInBackground`, sanitize `fileItem.getName()` via `new File(fileItem.getName()).getName()`, and verify canonical containment inside `previewDir`.

### CB-SEC-007: Unauthenticated Localhost Media Streaming Server and Non-Constant-Time SAF WebDAV Auth Check
- **Fingerprint**: `local-loopback-services-weak-or-unauthenticated-access`
- **Severity**: **LOW** (Likelihood: Medium | Impact: Low | Confidence: High)
- **Vulnerability Class**: Unauthenticated Local Loopback Service / Timing Side-Channel (`CWE-306`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java:1956-1968`, `app/src/main/java/ca/pkay/rcloneexplorer/Services/StreamingService.java:100`, and `safdav/src/main/java/io/github/x0b/safdav/SafDAVServer.java:92-98`
- **Source Trace**:
  1. `entrypoint` — `FileExplorerFragment.StreamTask.doInBackground` (`FileExplorerFragment.java:1956`) creates `serveIntent` for `StreamingService` on `127.0.0.1:8080` without setting credentials or a random `baseUrl` capability token.
  2. `propagation` — `StreamingService.java:100` calls `rclone.serve(..., null)` with `baseUrl = null`.
  3. `sink` — `Rclone.java:733` starts `rclone serve http --addr 127.0.0.1:8080` with no authentication; separately, `SafDAVServer.java:97` compares Basic auth headers via `requiredAuthHeader.equals(suppliedAuthHeader)`.
- **Root Cause & Impact**: Unlike `ThumbnailsLoadingService` (`ThumbnailsLoadingService.java:78-97`), which generates a 16-byte `SecureRandom` `thumbnailServerAuth` path token passed via `--baseurl`, `StreamingService` serves media on `127.0.0.1:8080` with no capability path prefix. Any local app with `android.permission.INTERNET` can fetch the streamed file from `127.0.0.1:8080`.
- **Minimal Remediation**: Generate a 16-byte `SecureRandom` hex/URL-safe `baseUrl` capability token in `StreamingService` / `StreamTask` when no credentials are set, pass it to `rclone.serve(..., baseUrl)`, and use `MessageDigest.isEqual` in `SafDAVServer.checkAuthorization`.

### CB-SEC-008: Missing End-of-Options Separator and Remote Name Validation Enables Rclone CLI Flag Injection
- **Fingerprint**: `rclone-remote-name-flag-injection-in-cli-invocations`
- **Severity**: **LOW** (Likelihood: Low | Impact: Medium | Confidence: High)
- **Vulnerability Class**: Argument / Flag Injection (`CWE-88`)
- **Affected Location**: `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java:668, 1326, 1409` and `app/src/main/java/ca/pkay/rcloneexplorer/RemoteConfig/DynamicRemoteConfigFragment.kt:513`
- **Source Trace**:
  1. `entrypoint` — `DynamicRemoteConfigFragment.setUpRemote` (`DynamicRemoteConfigFragment.kt:513`) and `Rclone.copyConfigFile` (`Rclone.java:1490`) accept remote names without rejecting leading `-` characters.
  2. `propagation` — `Rclone.listDirectories` (`Rclone.java:1409`), `Rclone.deleteRemote` (`Rclone.java:668`), and `Rclone.reconnectRemote` (`Rclone.java:1326`) pass `remoteName` directly to `createCommand` without a `"--"` separator.
  3. `sink` — `Rclone.getRuntimeProcess` (`Rclone.java:504`) executes `librclone.so`, whose flag parser interprets arguments beginning with `-` as CLI flags.
- **Minimal Remediation**: Validate remote names against `^[A-Za-z0-9_][A-Za-z0-9_. -]*$` on creation and import, and insert `"--"` before positional remote arguments in `Rclone.java`.

## 5. Needs-Validation Findings (`CB-VAL-001` to `CB-VAL-002`)

1. **`CB-VAL-001` (`signing-key-rotation-and-ci-secret-verification`)**:
   - **Location**: `.github/workflows/android.yml:80-95` and `app/build.gradle:14-25, 76`.
   - **Issue**: Verify in GitHub repository settings that `secrets.CB_KEYSTORE_BASE64` contains a newly generated signing key distinct from the historically committed `roundsync.keystore`. In code, harden `app/build.gradle:76` and `.github/workflows/android.yml:82` so CI release builds fail immediately if `CB_KEYSTORE_BASE64` is missing or empty rather than falling back to `signingConfigs.debug`.
2. **`CB-VAL-002` (`rclone-fork-commit-and-transport-integrity`)**:
   - **Location**: `gradle.properties:33-34` and `rclone/build.gradle:230-256`.
   - **Issue**: Harden `rclone/build.gradle` so `getRcloneSource` validates `RCLONE_REF ==~ /^[0-9a-f]{40}$/` and asserts that `git rev-parse HEAD` after checkout equals `RCLONE_REF`.

## 6. Remaining Reliability & Crash Backlog (`CB-REL-001` to `CB-REL-005`)

During our code inspection against `AUDIT_AND_REMEDIATION_PLAN.md`, we verified that **5 crash/stability defects** remain unfixed in commit `ece9275441e448ec520a7cbc336c59bfe643b22c`. Including these in Wave 5 of the subagent plan ensures all remaining issues in the app are resolved:

| ID | File & Line | Defect Summary | Exact Fix |
| :--- | :--- | :--- | :--- |
| **CB-REL-001** | `MainActivity.java:361` | `onActivityResult` for `SETTINGS_CODE` calls `data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false)` without checking `if (data != null)`, causing an NPE when `SettingsActivity` finishes with `RESULT_CANCELED` (`data == null`). | Guard line 361 with `if (data != null && data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false))`. |
| **CB-REL-002** | `EphemeralWorker.kt:78, 152` & `SyncWorker.kt:74, 148` | `log2File` is declared `private var log2File: Log2File? = null` and never initialized in `doWork()` (so `log2File?.log(...)` silently drops all worker logs); additionally, `unregisterReceiver(connectivityChangeBroadcastReceiver)` in `finishWork()` is not guarded against `IllegalArgumentException` when `onStopped()` races with normal completion. | Initialize `log2File = Log2File(mContext)` in `doWork()` and wrap `mContext.unregisterReceiver(...)` in `try { ... } catch (_: IllegalArgumentException) {}`. |
| **CB-REL-003** | `ThumbnailsLoadingService.java:108-121` | `onHandleIntent` calls `process.exitValue()` immediately after `process.waitFor()` returns, even if `waitFor()` threw `InterruptedException` during service shutdown (`onDestroy`), throwing `IllegalThreadStateException`. | Track `boolean exited = false;` inside the `try { process.waitFor(); exited = true; }` block and guard `if (exited && process.exitValue() != 0)`. |
| **CB-REL-004** | `VirtualContentProvider.java:538` | `getRemoteItem(DocumentId)` calls `rclone.getRemoteItemFromName(id.getRemote())` and immediately checks `if (remote.isCrypt())` without a null check, throwing an NPE if a remote was deleted or not yet loaded. | Add `if (remote == null) { throw new FileNotFoundException("Remote not found: " + id.getRemote()); }` before `remote.isCrypt()`. |
| **CB-REL-005** | `Rclone.java:786` (`sync`) | `Rclone.sync()` constructs `createCommandWithOptions(command, ...)` where `localPath` is passed without `encodePath(localPath)` (unlike `uploadFile` and `downloadFile`) and without `"--"` before positional arguments. | Apply safe path/argument handling and `"--"` delimiter consistently in `Rclone.java`. |

---

## 7. Multi-Agent Looping Remediation Plan for Weaker Subagents

Weaker or smaller coding models (such as 8B–70B local models or fast tier models like Haiku/Flash) fail when given multi-file refactors, open-ended architectural prompts, or implicit build dependencies. They succeed with high reliability when constrained inside a **deterministic Orchestrator–Worker–Verifier–Critic loop** where every micro-task touches **at most one file**, provides the **exact line window and before/after anchor**, and runs a **fast, binary verification gate** after every edit.

### 7.1 Loop Architecture & Finite State Machine

```
                  +---------------------------------------+
                  |     ORCHESTRATOR (Loop Controller)    |
                  |  Reads loop-state.json, selects next  |
                  |  PENDING micro-task in wave order     |
                  +-------------------+-------------------+
                                      |
                                      v
                  +---------------------------------------+
                  |   1. CONTEXT SLICER (Deterministic)   |
                  |  Extracts target file +-30 lines      |
                  |  around target locus + task spec      |
                  +-------------------+-------------------+
                                      |
                                      v
                  +---------------------------------------+
                  |   2. WORKER SUBAGENT (Weaker Model)   |
                  |  Applies single-file SEARCH/REPLACE   |
                  |  block; forbidden from touching other |
                  |  files or reformatting unrelated code |
                  +-------------------+-------------------+
                                      |
                                      v
                  +---------------------------------------+
                  |  3. VERIFIER SUBAGENT / SHELL GATE    |
                  |  Runs: (a) git diff scope check,      |
                  |  (b) regex/AST invariant check,       |
                  |  (c) module compile/unit test gate    |
                  +---------+-------------------+---------+
                            |                   |
                       [GATE PASS]         [GATE FAIL]
                            |                   |
                            v                   v
                  +-------------------+   +-------------------------------+
                  | 4. CRITIC SUBAGENT|   | Increment attempt_count       |
                  | Checks diff for   |   | git checkout -- <target_file> |
                  | regressions/stubs |   | If attempts < 2: feed exact   |
                  +----+---------+----+   | stderr back to Worker.        |
                       |         |        | If attempts == 2: mark        |
                  [APPROVED] [REJECTED]---| BLOCKED_ESCALATE & halt wave. |
                       |                  +-------------------------------+
                       v
                  +---------------------------------------+
                  | 5. COMMIT & ADVANCE                   |
                  | git commit -m "fix(<id>): ..."        |
                  | Update loop-state.json -> COMPLETED   |
                  +---------------------------------------+
```

### 7.2 Operating Rules & Guardrails for Weaker Subagents

1. **Single-File Ownership Rule**: Every micro-task (`TASK-W*-*`) modifies **exactly one file** (except Wave 4 Task `TASK-W4-01`, which creates one new helper file `CredentialStore.java` before `TASK-W4-02` and `TASK-W4-03` wire it into single files). If `git status --porcelain` shows more than the 1 allowed file modified, the Verifier automatically rejects the iteration and runs `git checkout -- .`.
2. **Bounded Context Window (`<= 120 lines`)**: Never feed an entire 2,000-line file like `Rclone.java` or `FileExplorerFragment.java` to a weaker subagent. The Context Slicer extracts only the target line range (`start_line - 20` to `end_line + 20`) with line numbers prepended.
3. **Strict `SEARCH / REPLACE` Patch Format**: Weaker models frequently miscalculate unified diff line counts (`@@ -x,y +a,b @@`). Require the Worker subagent to emit only a single fenced block using literal `<<<<<<< SEARCH`, `=======`, `>>>>>>> REPLACE` markers matching exact lines from the provided slice.
4. **Two-Strike Rollback & Failure Memory**:
   - On **Attempt 1 Failure**: Roll back the working tree (`git checkout -- <target_file>`), capture the exact Verifier/Critic failure message (max 20 lines), and pass it in the `PREVIOUS_ATTEMPT_ERROR` slot on Attempt 2.
   - On **Attempt 2 Failure**: Roll back immediately, set task status to `BLOCKED_ESCALATE` in `loop-state.json`, and stop the wave for human/strong-model review rather than letting the weaker model loop infinitely.
5. **Anti-Hallucination Prohibitions**:
   - Never add new Maven/Gradle dependencies unless explicitly specified in the micro-task.
   - Never delete existing methods, change method signatures used by other classes, or replace logic with `// TODO` or `...` placeholders.

### 7.3 Shared Loop State Schema (`loop-state.json`)

Save the initial state tracker at `scripts/loop-state.json` (or `.remediation/loop-state.json`):

```json
{
  "repo_commit_base": "ece9275441e448ec520a7cbc336c59bfe643b22c",
  "max_attempts_per_task": 2,
  "current_wave": 1,
  "tasks": [
    {
      "task_id": "TASK-W1-01",
      "finding_id": "CB-SEC-001",
      "wave": 1,
      "target_file": "app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java",
      "line_window": [220, 290],
      "status": "PENDING",
      "attempts": 0,
      "last_error": null,
      "commit_sha": null
    },
    {
      "task_id": "TASK-W1-02",
      "finding_id": "CB-SEC-002",
      "wave": 1,
      "target_file": ".github/workflows/translations.yml",
      "line_window": [40, 75],
      "status": "PENDING",
      "attempts": 0,
      "last_error": null,
      "commit_sha": null
    },
    {
      "task_id": "TASK-W1-03",
      "finding_id": "CB-SEC-003",
      "wave": 1,
      "target_file": "app/src/main/AndroidManifest.xml",
      "line_window": [50, 70],
      "status": "PENDING",
      "attempts": 0,
      "last_error": null,
      "commit_sha": null
    }
  ]
}
```

### 7.4 Copy-Pasteable Subagent Prompt Templates

#### Template A: Worker Subagent Prompt (Code Patcher)

```markdown
You are a focused Code Patch Worker Subagent. You must fix exactly one issue in a single file.

### STRICT CONSTRAINTS
1. You may ONLY modify `{{TARGET_FILE}}`.
2. Do NOT add placeholders (`// TODO`, `...`, `/* existing code */`).
3. Do NOT change unrelated methods, imports, or formatting outside the target block.
4. Output ONLY one `SEARCH/REPLACE` block in the exact format below:

<<<<<<< SEARCH
<exact lines copied verbatim from the CURRENT FILE SLICE without line numbers>
=======
<replacement lines>
>>>>>>> REPLACE

### TASK SPECIFICATION
- **Task ID**: `{{TASK_ID}}` (`{{FINDING_ID}}`)
- **Target File**: `{{TARGET_FILE}}` (Lines `{{START_LINE}}`–`{{END_LINE}}`)
- **Objective**: {{TASK_OBJECTIVE}}
- **Required Invariant**: {{REQUIRED_INVARIANT}}
- **Previous Attempt Error (if retry)**: {{PREVIOUS_ATTEMPT_ERROR}}

### CURRENT FILE SLICE (`{{TARGET_FILE}}:{{SLICE_START}}-{{SLICE_END}}`)
```java
{{FILE_SLICE_WITH_RAW_LINES}}
```
```

#### Template B: Verifier Subagent Prompt (Deterministic Gate)

```markdown
You are the Deterministic Verifier Subagent. Evaluate the candidate patch applied to `{{TARGET_FILE}}` for task `{{TASK_ID}}`.

### INPUTS
1. `git status --porcelain` output:
```text
{{GIT_STATUS_OUTPUT}}
```
2. `git diff` output:
```diff
{{GIT_DIFF_OUTPUT}}
```
3. Verification command output (exit code `{{VERIFY_EXIT_CODE}}`):
```text
{{VERIFY_COMMAND_OUTPUT}}
```

### DECISION RULES
1. If more than `{{TARGET_FILE}}` is modified in `git status --porcelain`, return `VERDICT: FAIL`.
2. If `{{VERIFY_EXIT_CODE}}` is non-zero, return `VERDICT: FAIL`.
3. If the diff contains `TODO`, `FIXME`, `...`, or deletes unrelated methods, return `VERDICT: FAIL`.
4. If the required security invariant (`{{REQUIRED_INVARIANT}}`) is not satisfied in `git diff`, return `VERDICT: FAIL`.
5. Otherwise, return `VERDICT: PASS`.

Output strictly in JSON:
{"verdict": "PASS" | "FAIL", "reason": "<1-sentence concrete explanation>"}
```

#### Template C: Critic Subagent Prompt (Semantic & Regression Reviewer)

```markdown
You are the Diff Critic Subagent. The deterministic Verifier passed `{{TASK_ID}}` on `{{TARGET_FILE}}`. Perform a final semantic check before commit.

### DIFF UNDER REVIEW
```diff
{{GIT_DIFF_OUTPUT}}
```

### CHECKLIST
1. Does the change completely close `{{FINDING_ID}}` without introducing a NullPointerException, resource leak, or UI thread block?
2. Are all variables, imports, and exception types used in the replacement valid in this file's scope?
3. Is the diff minimal (no accidental deletion of adjacent statements)?

Output strictly in JSON:
{"verdict": "APPROVE" | "REJECT", "feedback_for_worker": "&lt;exact line or symbol to fix if rejected&gt;"}
```

### 7.5 Automated Loop Controller Script (`scripts/run-remediation-loop.sh`)

This POSIX-compatible controller script orchestrates the Slicer -> Worker -> Verifier -> Critic loop and enforces the 2-strike rollback rule:

```bash
#!/usr/bin/env bash
set -euo pipefail

STATE_FILE=".remediation/loop-state.json"
MAX_ATTEMPTS=2

apply_search_replace() {
  local target_file="$1"
  local patch_file="$2"
  python3 - "$target_file" "$patch_file" << 'PYEOF'
import sys

target_path, patch_path = sys.argv[1], sys.argv[2]
with open(target_path, "r", encoding="utf-8") as f:
    content = f.read()
with open(patch_path, "r", encoding="utf-8") as f:
    patch = f.read()

s_marker = "<<<<<<< SEARCH
"
m_marker = "
=======
"
e_marker = "
>>>>>>> REPLACE"

s_idx = patch.find(s_marker)
m_idx = patch.find(m_marker)
e_idx = patch.find(e_marker)
if s_idx == -1 or m_idx == -1 or e_idx == -1:
    sys.exit("ERROR: Missing SEARCH/REPLACE markers in worker output")

search_block = patch[s_idx + len(s_marker):m_idx]
replace_block = patch[m_idx + len(m_marker):e_idx]

count = content.count(search_block)
if count == 0:
    sys.exit("ERROR: SEARCH block not found verbatim in target file")
if count > 1:
    sys.exit("ERROR: SEARCH block matched multiple locations; include more context lines")

updated = content.replace(search_block, replace_block, 1)
with open(target_path, "w", encoding="utf-8") as f:
    f.write(updated)
print("APPLIED_OK")
PYEOF
}

verify_single_file_scope() {
  local expected_file="$1"
  local changed
  changed=$(git status --porcelain | awk '{print $2}')
  if [ "$changed" != "$expected_file" ]; then
    echo "SCOPE_VIOLATION: expected only $expected_file, got: $changed"
    return 1
  fi
  return 0
}
```

### 7.6 Ordered Micro-Task Specifications (Waves 1–5, `TASK-W1-01` to `TASK-W5-05`)

Every micro-task below is self-contained and ready to feed directly into Template A (`Worker Subagent`) and Template B (`Verifier Subagent`).

| Wave | Task ID | Finding ID | Target File | Line Window | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Wave 1** | `TASK-W1-01` | `CB-SEC-001` | `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java` | `230–285` | Sanitize `DISPLAY_NAME`, block internal `file://` URIs, stage into internal `getCacheDir()/share_staging` with canonical path check |
| **Wave 1** | `TASK-W1-02` | `CB-SEC-002` | `.github/workflows/translations.yml` | `43–70` | Eliminate inline `${{ ... }}` shell command injection in PR translation workflow |
| **Wave 1** | `TASK-W1-03` | `CB-SEC-003` | `app/src/main/AndroidManifest.xml` | `55–66` | Set `android:exported="false"` on headless `ShortcutServiceActivity` |
| **Wave 2** | `TASK-W2-01` | `CB-SEC-005` | `app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java` | `130–160` | Move `rcd.log` to internal `getFilesDir()/logs` and remove `--dump headers` |
| **Wave 2** | `TASK-W2-02` | `CB-SEC-005` | `app/src/main/java/ca/pkay/rcloneexplorer/Log2File.java` | `20–32` | Move `log.txt` from `getExternalFilesDir("logs")` to internal `getFilesDir()/logs` |
| **Wave 2** | `TASK-W2-03` | `CB-SEC-005` | `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java` | `765–776` | Move `serve.log` from `getExternalFilesDir("logs")` to internal `getFilesDir()/logs` |
| **Wave 2** | `TASK-W2-04` | `CB-SEC-006` | `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java` | `1850–1880` | Move `DownloadAndOpen` preview staging to internal `getCacheDir()/preview` and sanitize filename |
| **Wave 2** | `TASK-W2-05` | `CB-SEC-007` | `safdav/src/main/java/io/github/x0b/safdav/SafDAVServer.java` | `90–100` | Replace `String.equals` with constant-time `MessageDigest.isEqual` in `SafDAVServer.checkAuthorization` |
| **Wave 2** | `TASK-W2-06` | `CB-SEC-007` | `app/src/main/java/ca/pkay/rcloneexplorer/Services/StreamingService.java` | `85–110` | Add `SERVE_BASE_URL` extra support in `StreamingService` and pass to `rclone.serve` |
| **Wave 2** | `TASK-W2-07` | `CB-SEC-007` | `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java` | `1950–1975` | Generate 16-byte `SecureRandom` capability `baseUrl` in `StreamTask` and append to streaming `Uri` |
| **Wave 3** | `TASK-W3-01` | `CB-SEC-008` | `app/src/main/java/ca/pkay/rcloneexplorer/RemoteConfig/DynamicRemoteConfigFragment.kt` | `510–530` | Reject remote names starting with `-` or containing invalid characters on creation |
| **Wave 3** | `TASK-W3-02` | `CB-SEC-008` | `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java` | `665–675, 1325–1333, 1408–1415` | Reject leading-hyphen remote names and insert `"--"` before positional args in `Rclone.java` |
| **Wave 3** | `TASK-W3-03` | `CB-VAL-001` | `.github/workflows/android.yml` | `78–96` | Fail fast in CI if `CB_KEYSTORE_BASE64` or signing secrets are empty and verify keystore with `keytool` |
| **Wave 3** | `TASK-W3-04` | `CB-VAL-001` | `app/build.gradle` | `14–25, 70–78` | Fail release build when `System.getenv('CI') == 'true'` and `!releaseKeyConfigured` |
| **Wave 3** | `TASK-W3-05` | `CB-VAL-002` | `rclone/build.gradle` | `240–265` | Assert `RCLONE_REF` is a 40-hex-char SHA and verify `git rev-parse HEAD == RCLONE_REF` |
| **Wave 4** | `TASK-W4-01` | `CB-SEC-004` | `safdav/src/main/java/io/github/x0b/safdav/SafAccessProvider.java` | `35–53` | Encrypt `safDavPass` at rest using an Android Keystore AES/GCM key (`AndroidKeyStore`) |
| **Wave 4** | `TASK-W4-02` | `CB-SEC-004` | `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java` | `1530–1585` | Prevent `decryptConfig` from permanently overwriting an encrypted `rclone.conf` with unencrypted plaintext on disk |
| **Wave 5** | `TASK-W5-01` | `CB-REL-001` | `app/src/main/java/ca/pkay/rcloneexplorer/Activities/MainActivity.java` | `355–370` | Fix NPE in `MainActivity.onActivityResult` (`SETTINGS_CODE`) when `data == null` |
| **Wave 5** | `TASK-W5-02` | `CB-REL-002` | `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/EphemeralWorker.kt` | `75–160` | Initialize `log2File = Log2File(mContext)` and guard `unregisterReceiver` in `EphemeralWorker.kt` |
| **Wave 5** | `TASK-W5-03` | `CB-REL-002` | `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SyncWorker.kt` | `70–152` | Initialize `log2File = Log2File(mContext)` and guard `unregisterReceiver` in `SyncWorker.kt` |
| **Wave 5** | `TASK-W5-04` | `CB-REL-003` | `app/src/main/java/ca/pkay/rcloneexplorer/Services/ThumbnailsLoadingService.java` | `105–125` | Guard `process.exitValue()` when `process.waitFor()` is interrupted in `ThumbnailsLoadingService` |
| **Wave 5** | `TASK-W5-05` | `CB-REL-004` | `app/src/main/java/ca/pkay/rcloneexplorer/VirtualContentProvider.java` | `532–546` | Add null check in `VirtualContentProvider.getRemoteItem` before `remote.isCrypt()` |

---

#### Wave 1 Detailed Micro-Task Specs (High/Medium External Exposure & CI Injection)

##### `TASK-W1-01` — Fix `SharingActivity` Path Traversal, `file://` Internal URI Staging & External Cache Exposure (`CB-SEC-001`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java`
- **Line Window**: `233–284`
- **Forbidden Actions**: Do not modify `onCreate` UI flow or fragment transactions; only modify `CopyFile.doInBackground` and `CopyFile.resolveName`.
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
                try {
                    fileName = resolveName(uri);
                } catch (SecurityException e) {
                    success = false;
                    continue;
                }
                // todo: encrypt external cache
                File cacheDir = getExternalCacheDir();
                File outFile = new File(cacheDir, fileName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 64 * 1024)) {
                    if (null == in) {
                        success = false;
                        continue;
                    }
                    uploadList.add(outFile.getAbsolutePath());
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                } catch (IOException e) {
                    FLog.e(TAG, "Copy error: ", e);
                    success = false;
                }
            }
            return success;
        }

        @NonNull
        private String resolveName(@NonNull Uri uri) {
            String[] projection = {OpenableColumns.DISPLAY_NAME};
            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (null != cursor && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(index>=0) {
                        String name = cursor.getString(index);
                        if (null != name) {
                            return name;
                        }
                    }
                }
            }
            List<String> segments = uri.getPathSegments();
            if (segments.size() >= 1) {
                return segments.get(segments.size() - 1);
            }
            return "unnamed";
        }
=======
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    try {
                        String canonicalSrc = new File(uri.getPath()).getCanonicalPath();
                        String internalDataDir = new File(getApplicationInfo().dataDir).getCanonicalPath();
                        if (canonicalSrc.equals(internalDataDir) || canonicalSrc.startsWith(internalDataDir + File.separator)) {
                            FLog.e(TAG, "Copy error: rejected internal file URI");
                            success = false;
                            continue;
                        }
                    } catch (IOException e) {
                        FLog.e(TAG, "Copy error: failed to canonicalize file URI", e);
                        success = false;
                        continue;
                    }
                }

                try {
                    fileName = resolveName(uri);
                } catch (SecurityException e) {
                    success = false;
                    continue;
                }
                File cacheDir = new File(getCacheDir(), "share_staging");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    success = false;
                    continue;
                }
                File outFile = new File(cacheDir, fileName);
                try {
                    String canonicalCacheDir = cacheDir.getCanonicalPath() + File.separator;
                    if (!outFile.getCanonicalPath().startsWith(canonicalCacheDir)) {
                        FLog.e(TAG, "Copy error: rejected path traversal in display name");
                        success = false;
                        continue;
                    }
                } catch (IOException e) {
                    FLog.e(TAG, "Copy error: canonical path check failed", e);
                    success = false;
                    continue;
                }
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 64 * 1024)) {
                    if (null == in) {
                        success = false;
                        continue;
                    }
                    uploadList.add(outFile.getAbsolutePath());
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                } catch (IOException e) {
                    FLog.e(TAG, "Copy error: ", e);
                    success = false;
                }
            }
            return success;
        }

        @NonNull
        private String sanitizeFileName(@Nullable String rawName) {
            if (rawName == null) {
                return "unnamed";
            }
            String cleaned = rawName.replace('\', '/');
            String baseName = new File(cleaned).getName().trim();
            if (baseName.isEmpty() || ".".equals(baseName) || "..".equals(baseName)) {
                return "unnamed";
            }
            return baseName;
        }

        @NonNull
        private String resolveName(@NonNull Uri uri) {
            String[] projection = {OpenableColumns.DISPLAY_NAME};
            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (null != cursor && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(index>=0) {
                        String name = cursor.getString(index);
                        if (null != name) {
                            return sanitizeFileName(name);
                        }
                    }
                }
            }
            List<String> segments = uri.getPathSegments();
            if (segments.size() >= 1) {
                return sanitizeFileName(segments.get(segments.size() - 1));
            }
            return "unnamed";
        }
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  grep -q 'new File(getCacheDir(), "share_staging")' app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java &&   grep -q 'getCanonicalPath().startsWith(canonicalCacheDir)' app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java &&   ! grep -q 'getExternalCacheDir()' app/src/main/java/ca/pkay/rcloneexplorer/Activities/SharingActivity.java
  ```

##### `TASK-W1-02` — Fix Command Injection in `.github/workflows/translations.yml` (`CB-SEC-002`)
- **Target File**: `.github/workflows/translations.yml`
- **Line Window**: `42–70`
- **Exact `SEARCH / REPLACE` Specification**:
```yaml
<<<<<<< SEARCH
      - name: Prepare Profanity Check
        id: prep
        run: |
          sudo apt-get install -y xmlstarlet
          pip3 install alt-profanity-check
          chmod +x ./scripts/checkProfanity.py
          chmod +x ./scripts/generateFilelist.sh
          FILE_LIST="${{needs.string-change-verification.outputs.files}}"
          SIZE=$(echo "$FILE_LIST" | awk '{print NF}')
          if [ "$SIZE" -ne 1 ]; then
            echo "Only pass the amount of commits and one translation file! You passed: $SIZE"
            exit 1
          fi
          TRANSLATIONS=$(./scripts/generateFilelist.sh ${{ github.event.pull_request.commits }} ${{needs.string-change-verification.outputs.files}})
          echo "TRANSLATIONS=$TRANSLATIONS" >> $GITHUB_OUTPUT
        shell: sh
      - uses: fabasoad/translation-action@1476a9db97feaa816029bc027103eb89f3b017bf # v4.2.4
        id: google-translate
        with:
          provider: google
          lang: auto-en
          source: ${{ steps.prep.outputs.TRANSLATIONS }}
      - name: Print the result
        run: |
          echo "Translations are: '${{ steps.google-translate.outputs.text }}'"
          echo "${{ steps.google-translate.outputs.text }}" | sed 's/ ; /
/g' > translated_texts.txt
          ./scripts/checkProfanity.py translated_texts.txt
        shell: sh
=======
      - name: Prepare Profanity Check
        id: prep
        env:
          CHANGED_FILES: ${{ needs.string-change-verification.outputs.files }}
          PR_COMMITS: ${{ github.event.pull_request.commits }}
        run: |
          sudo apt-get install -y xmlstarlet
          pip3 install alt-profanity-check
          chmod +x ./scripts/checkProfanity.py
          chmod +x ./scripts/generateFilelist.sh
          SIZE=$(printf '%s
' "$CHANGED_FILES" | awk '{print NF}')
          if [ "$SIZE" -ne 1 ]; then
            echo "Only pass the amount of commits and one translation file! You passed: $SIZE"
            exit 1
          fi
          SINGLE_FILE=$(printf '%s
' "$CHANGED_FILES" | awk '{print $1}')
          TRANSLATIONS=$(./scripts/generateFilelist.sh "$PR_COMMITS" "$SINGLE_FILE")
          {
            echo "TRANSLATIONS<<EOF_TRANSLATIONS"
            printf '%s
' "$TRANSLATIONS"
            echo "EOF_TRANSLATIONS"
          } >> "$GITHUB_OUTPUT"
        shell: sh
      - uses: fabasoad/translation-action@1476a9db97feaa816029bc027103eb89f3b017bf # v4.2.4
        id: google-translate
        with:
          provider: google
          lang: auto-en
          source: ${{ steps.prep.outputs.TRANSLATIONS }}
      - name: Print the result
        env:
          TRANSLATED_TEXT: ${{ steps.google-translate.outputs.text }}
        run: |
          printf 'Translations are: %s
' "$TRANSLATED_TEXT"
          printf '%s
' "$TRANSLATED_TEXT" | sed 's/ ; /
/g' > translated_texts.txt
          ./scripts/checkProfanity.py translated_texts.txt
        shell: sh
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  ! grep -n 'echo .*steps\.google-translate\.outputs\.text' .github/workflows/translations.yml &&   grep -q 'TRANSLATED_TEXT: ${{ steps.google-translate.outputs.text }}' .github/workflows/translations.yml
  ```

##### `TASK-W1-03` — Lock Down Exported `ShortcutServiceActivity` (`CB-SEC-003`)
- **Target File**: `app/src/main/AndroidManifest.xml`
- **Line Window**: `55–66`
- **Exact `SEARCH / REPLACE` Specification**:
```xml
<<<<<<< SEARCH
        <activity
            android:name="ca.pkay.rcloneexplorer.Activities.ShortcutServiceActivity"
            android:theme="@style/HiddenWindowShortcutSyncStartTheme"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
            </intent-filter>
        </activity>
=======
        <activity
            android:name="ca.pkay.rcloneexplorer.Activities.ShortcutServiceActivity"
            android:theme="@style/HiddenWindowShortcutSyncStartTheme"
            android:exported="false" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
            </intent-filter>
        </activity>
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  python3 -c '
  import xml.etree.ElementTree as ET
  tree = ET.parse("app/src/main/AndroidManifest.xml")
  ns = {"android": "http://schemas.android.com/apk/res/android"}
  for act in tree.findall(".//activity"):
      if act.attrib.get(f"{{{ns["android"]}}}name") == "ca.pkay.rcloneexplorer.Activities.ShortcutServiceActivity":
          assert act.attrib.get(f"{{{ns["android"]}}}exported") == "false"
  '
  ```

---

#### Wave 2 Detailed Micro-Task Specs (External Log & Cache Isolation + Loopback Daemon Hardening)

##### `TASK-W2-01` — Remove `--dump headers` and Move `rcd.log` to Internal Storage (`CB-SEC-005`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java`
- **Line Window**: `132–158`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
            String tmpDir = context.getCacheDir().getAbsolutePath();
            String logFile = context.getExternalFilesDir("logs").getAbsolutePath() + "/rcd.log";
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String transfers = pref.getString(context.getString(R.string.pref_key_transfers), "4");
            ArrayList<String> parameters = new ArrayList<>(Arrays.asList(
                    rclone,
                    "--config", configPath,
                    "--rc-addr", addr,
                    "--rc-user", rcUser,
                    "--rc-pass", rcPass,
                    "--rc-serve",
                    // Transfer throughput tuning for VCP/rcd-driven operations.
                    "--transfers", transfers,
                    "--buffer-size", "16M",
                    "--multi-thread-streams", "4"));
            if (pref.getBoolean(context.getString(R.string.pref_key_logs), false)) {
                parameters.addAll(Arrays.asList(
                        "--log-file", logFile,
                        "--dump", "headers",
                        "-vvv"));
            }
=======
            String tmpDir = context.getCacheDir().getAbsolutePath();
            File logsDir = new File(context.getFilesDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            String logFile = new File(logsDir, "rcd.log").getAbsolutePath();
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String transfers = pref.getString(context.getString(R.string.pref_key_transfers), "4");
            ArrayList<String> parameters = new ArrayList<>(Arrays.asList(
                    rclone,
                    "--config", configPath,
                    "--rc-addr", addr,
                    "--rc-user", rcUser,
                    "--rc-pass", rcPass,
                    "--rc-serve",
                    // Transfer throughput tuning for VCP/rcd-driven operations.
                    "--transfers", transfers,
                    "--buffer-size", "16M",
                    "--multi-thread-streams", "4"));
            if (pref.getBoolean(context.getString(R.string.pref_key_logs), false)) {
                parameters.addAll(Arrays.asList(
                        "--log-file", logFile,
                        "-vv"));
            }
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  ! grep -q '"--dump"' app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java &&   ! grep -q 'getExternalFilesDir("logs")' app/src/main/java/ca/pkay/rcloneexplorer/RcloneRcd.java
  ```

##### `TASK-W2-02` — Move `Log2File` (`log.txt`) to Internal App Storage (`CB-SEC-005`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Log2File.java`
- **Line Window**: `22–32`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
    public void log(String message) {
        File path = context.getExternalFilesDir("logs");
        if (path == null) {
            return;
        }
        File logFile = new File(path, "log.txt");
=======
    public void log(String message) {
        File path = new File(context.getFilesDir(), "logs");
        if (!path.exists() && !path.mkdirs()) {
            return;
        }
        File logFile = new File(path, "log.txt");
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  ! grep -q 'getExternalFilesDir("logs")' app/src/main/java/ca/pkay/rcloneexplorer/Log2File.java &&   grep -q 'new File(context.getFilesDir(), "logs")' app/src/main/java/ca/pkay/rcloneexplorer/Log2File.java
  ```

##### `TASK-W2-03` — Move `Rclone.serve` (`serve.log`) to Internal App Storage (`CB-SEC-005`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java`
- **Line Window**: `766–775`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
        boolean isLoggingEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);
        if (isLoggingEnabled) {
            File serveLog = new File(context.getExternalFilesDir("logs"), "serve.log");
            params.add("--log-file");
            params.add(serveLog.getAbsolutePath());
        }
=======
        boolean isLoggingEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);
        if (isLoggingEnabled) {
            File logsDir = new File(context.getFilesDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            File serveLog = new File(logsDir, "serve.log");
            params.add("--log-file");
            params.add(serveLog.getAbsolutePath());
        }
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  ! grep -q 'getExternalFilesDir("logs")' app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java
  ```

##### `TASK-W2-04` — Move `DownloadAndOpen` Preview Cache to Internal `getCacheDir()` with Filename Sanitization (`CB-SEC-006`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java`
- **Line Window**: `1850–1865`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            FileItem fileItem = fileItems[0];
            mimeType = fileItem.getMimeType();
            File[] extCacheDirs = ContextCompat.getExternalCacheDirs(context);
            if (extCacheDirs.length < 1) {
                return false;
            }
            String saveLocation = extCacheDirs[0].getAbsolutePath();

            fileLocation = saveLocation + "/" + fileItem.getName();
=======
        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            FileItem fileItem = fileItems[0];
            mimeType = fileItem.getMimeType();
            File previewCacheDir = new File(context.getCacheDir(), "preview");
            if (!previewCacheDir.exists() && !previewCacheDir.mkdirs()) {
                return false;
            }
            String safeFileName = new File(fileItem.getName().replace('\', '/')).getName().trim();
            if (safeFileName.isEmpty() || ".".equals(safeFileName) || "..".equals(safeFileName)) {
                return false;
            }
            String saveLocation = previewCacheDir.getAbsolutePath();
            File targetFile = new File(previewCacheDir, safeFileName);
            try {
                if (!targetFile.getCanonicalPath().startsWith(previewCacheDir.getCanonicalPath() + File.separator)) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
            fileLocation = targetFile.getAbsolutePath();
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  grep -q 'new File(context.getCacheDir(), "preview")' app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java &&   ! grep -q 'ContextCompat.getExternalCacheDirs(context)' app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java
  ```

##### `TASK-W2-05` — Constant-Time Basic Auth Check in `SafDAVServer.java` (`CB-SEC-007`)
- **Target File**: `safdav/src/main/java/io/github/x0b/safdav/SafDAVServer.java`
- **Line Window**: `91–99`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
    private boolean checkAuthorization(IHTTPSession session) {
        if(null == requiredAuthHeader) {
            return true;
        }
        String suppliedAuthHeader = session.getHeaders().get("authorization");
        return requiredAuthHeader.equals(suppliedAuthHeader);
    }
=======
    private boolean checkAuthorization(IHTTPSession session) {
        if (null == requiredAuthHeader) {
            return true;
        }
        String suppliedAuthHeader = session.getHeaders().get("authorization");
        if (null == suppliedAuthHeader) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                requiredAuthHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                suppliedAuthHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
>>>>>>> REPLACE
```
- **Deterministic Verification Gate**:
  ```bash
  grep -q 'MessageDigest.isEqual' safdav/src/main/java/io/github/x0b/safdav/SafDAVServer.java
  ```

##### `TASK-W2-06` & `TASK-W2-07` — Add Capability `baseUrl` Token to `StreamingService` & `StreamTask` (`CB-SEC-007`)
- **Target File (`TASK-W2-06`)**: `app/src/main/java/ca/pkay/rcloneexplorer/Services/StreamingService.java` (Lines `35–46` and `90–103`)
```java
<<<<<<< SEARCH
    public static final String SERVE_PORT = "serve_port";
    public static final String ALLOW_REMOTE_ACCESS = "allow_remote_access";
    public static final String AUTHENTICATION_USERNAME = "username";
    public static final String AUTHENTICATION_PASSWORD = "password";
=======
    public static final String SERVE_PORT = "serve_port";
    public static final String SERVE_BASE_URL = "serve_base_url";
    public static final String ALLOW_REMOTE_ACCESS = "allow_remote_access";
    public static final String AUTHENTICATION_USERNAME = "username";
    public static final String AUTHENTICATION_PASSWORD = "password";
>>>>>>> REPLACE
```
and in `onHandleIntent`:
```java
<<<<<<< SEARCH
        String username = intent.getStringExtra(AUTHENTICATION_USERNAME);
        String password = intent.getStringExtra(AUTHENTICATION_PASSWORD);
        int protocol = intent.getIntExtra(SERVE_PROTOCOL, SERVE_HTTP);
        boolean showNotificationText = intent.getBooleanExtra(SHOW_NOTIFICATION_TEXT, false);
        int port = intent.getIntExtra(SERVE_PORT, -1);
        int servePort = (port <= 0) ? rclone.allocatePort(8080, true) : port;
        runningProcess = rclone.serve(protocol, servePort, allowRemoteAccess, username, password, remote, servePath, null);
=======
        String username = intent.getStringExtra(AUTHENTICATION_USERNAME);
        String password = intent.getStringExtra(AUTHENTICATION_PASSWORD);
        String baseUrl = intent.getStringExtra(SERVE_BASE_URL);
        int protocol = intent.getIntExtra(SERVE_PROTOCOL, SERVE_HTTP);
        boolean showNotificationText = intent.getBooleanExtra(SHOW_NOTIFICATION_TEXT, false);
        int port = intent.getIntExtra(SERVE_PORT, -1);
        int servePort = (port <= 0) ? rclone.allocatePort(8080, true) : port;
        runningProcess = rclone.serve(protocol, servePort, allowRemoteAccess, username, password, remote, servePath, baseUrl);
>>>>>>> REPLACE
```
- **Target File (`TASK-W2-07`)**: `app/src/main/java/ca/pkay/rcloneexplorer/Fragments/FileExplorerFragment.java` (Lines `1954–1970`)
```java
<<<<<<< SEARCH
            fileItem = fileItems[0];
            int port = allocatePort(8080, true);
            serveIntent = new Intent(context, StreamingService.class);
            serveIntent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
            serveIntent.putExtra(StreamingService.REMOTE_ARG, remote);
            serveIntent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, false);
            serveIntent.putExtra(StreamingService.SERVE_PORT, port);
            // GH-87: Release old stream
            context.stopService(new Intent(context, StreamingService.class));
            tryStartService(context, serveIntent);

            Uri uri = Uri.parse("http://127.0.0.1:" + port)
                    .buildUpon()
                    .appendPath(fileItem.getName())
                    .build();
=======
            fileItem = fileItems[0];
            int port = allocatePort(8080, true);
            byte[] tokenBytes = new byte[16];
            new java.security.SecureRandom().nextBytes(tokenBytes);
            String streamToken = android.util.Base64.encodeToString(
                    tokenBytes, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING | android.util.Base64.URL_SAFE);
            serveIntent = new Intent(context, StreamingService.class);
            serveIntent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
            serveIntent.putExtra(StreamingService.REMOTE_ARG, remote);
            serveIntent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, false);
            serveIntent.putExtra(StreamingService.SERVE_PORT, port);
            serveIntent.putExtra(StreamingService.SERVE_BASE_URL, streamToken);
            // GH-87: Release old stream
            context.stopService(new Intent(context, StreamingService.class));
            tryStartService(context, serveIntent);

            Uri uri = Uri.parse("http://127.0.0.1:" + port)
                    .buildUpon()
                    .appendPath(streamToken)
                    .appendPath(fileItem.getName())
                    .build();
>>>>>>> REPLACE
```

---

#### Wave 3 Detailed Micro-Task Specs (CLI Argument Hygiene & CI/Gradle Build Guards)

##### `TASK-W3-01` — Validate Remote Name in `DynamicRemoteConfigFragment.kt` (`CB-SEC-008`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/RemoteConfig/DynamicRemoteConfigFragment.kt`
- **Line Window**: `510–520`
- **Exact `SEARCH / REPLACE` Specification**:
```kotlin
<<<<<<< SEARCH
    private fun setUpRemote() {

        val options = java.util.ArrayList<String>()
        val name: String = mRemoteName?.text.toString()
        options.add(name)
        options.add(mProviderTitle)
=======
    private fun setUpRemote() {

        val options = java.util.ArrayList<String>()
        val name: String = mRemoteName?.text.toString().trim()
        if (name.isEmpty() || name.startsWith("-") || !name.matches(Regex("^[A-Za-z0-9_][A-Za-z0-9_. -]*$"))) {
            es.dmoral.toasty.Toasty.error(mContext, "Invalid remote name", android.widget.Toast.LENGTH_SHORT, true).show()
            return
        }
        options.add(name)
        options.add(mProviderTitle)
>>>>>>> REPLACE
```

##### `TASK-W3-02` — Add Remote Name Validation & `"--"` Delimiter in `Rclone.java` (`CB-SEC-008`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java`
- **Line Window**: `666–676`, `1325–1333`, `1408–1415`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
    public void deleteRemote(String remoteName) {
        invalidateRemotesCache();
        String[] command = createCommandWithOptions("config", "delete", remoteName);
=======
    public void deleteRemote(String remoteName) {
        if (remoteName == null || remoteName.startsWith("-")) {
            FLog.e(TAG, "deleteRemote: rejected invalid remote name");
            return;
        }
        invalidateRemotesCache();
        String[] command = createCommandWithOptions("config", "delete", "--", remoteName);
>>>>>>> REPLACE
```
and for `reconnectRemote` / `listDirectories`:
```java
<<<<<<< SEARCH
    public Process reconnectRemote(RemoteItem remoteItem) {
        String[] command = createCommand("config", "update", remoteItem.getName());
=======
    public Process reconnectRemote(RemoteItem remoteItem) {
        if (remoteItem == null || remoteItem.getName() == null || remoteItem.getName().startsWith("-")) {
            return null;
        }
        String[] command = createCommand("config", "update", "--", remoteItem.getName());
>>>>>>> REPLACE
```
```java
<<<<<<< SEARCH
    public DirectoryProbeResult listDirectories(String remoteName, int maxDepth) {
        String[] command = createCommand("lsd", "--max-depth", String.valueOf(maxDepth), remoteName + ":");
=======
    public DirectoryProbeResult listDirectories(String remoteName, int maxDepth) {
        if (remoteName == null || remoteName.startsWith("-")) {
            return new DirectoryProbeResult(1, "Rejected invalid remote name");
        }
        String[] command = createCommand("lsd", "--max-depth", String.valueOf(maxDepth), "--", remoteName + ":");
>>>>>>> REPLACE
```

##### `TASK-W3-03` & `TASK-W3-04` — Enforce Non-Empty Release Signing Secrets in CI (`CB-VAL-001`)
- **Target File (`TASK-W3-03`)**: `.github/workflows/android.yml` (Lines `78–85`)
```yaml
<<<<<<< SEARCH
      - name: Configure release signing
        env:
          CB_KEYSTORE_BASE64: ${{ secrets.CB_KEYSTORE_BASE64 }}
        run: |
          mkdir -p app/.config/android
          echo "$CB_KEYSTORE_BASE64" | base64 -d > app/.config/android/cloudbridge-release.keystore
=======
      - name: Configure release signing
        env:
          CB_KEYSTORE_BASE64: ${{ secrets.CB_KEYSTORE_BASE64 }}
          CB_STORE_PASSWORD: ${{ secrets.CB_STORE_PASSWORD }}
          CB_KEY_ALIAS: ${{ secrets.CB_KEY_ALIAS }}
        run: |
          if [ -z "$CB_KEYSTORE_BASE64" ] || [ -z "$CB_STORE_PASSWORD" ] || [ -z "$CB_KEY_ALIAS" ]; then
            echo "ERROR: Release signing secrets (CB_KEYSTORE_BASE64, CB_STORE_PASSWORD, CB_KEY_ALIAS) must be set in CI." >&2
            exit 1
          fi
          mkdir -p app/.config/android
          printf '%s' "$CB_KEYSTORE_BASE64" | base64 -d > app/.config/android/cloudbridge-release.keystore
          keytool -list -keystore app/.config/android/cloudbridge-release.keystore -storepass "$CB_STORE_PASSWORD" -alias "$CB_KEY_ALIAS" >/dev/null
>>>>>>> REPLACE
```
- **Target File (`TASK-W3-04`)**: `app/build.gradle` (Lines `14–18`)
```groovy
<<<<<<< SEARCH
    def releaseStorePath = keystoreProperties['storeFile'] ?: System.getenv('CB_STORE_FILE')
    def releaseKeyConfigured = releaseStorePath != null && file(releaseStorePath).exists()
=======
    def releaseStorePath = keystoreProperties['storeFile'] ?: System.getenv('CB_STORE_FILE')
    def releaseKeyConfigured = releaseStorePath != null && file(releaseStorePath).exists() && file(releaseStorePath).length() > 0
    if (System.getenv('CI') == 'true' && !releaseKeyConfigured) {
        throw new GradleException("CI release build requires a valid release keystore (CB_STORE_FILE).")
    }
>>>>>>> REPLACE
```

##### `TASK-W3-05` — Verify 40-Character Commit Hash After `git checkout` in `rclone/build.gradle` (`CB-VAL-002`)
- **Target File**: `rclone/build.gradle`
- **Line Window**: `245–265`
- **Exact `SEARCH / REPLACE` Specification**:
```groovy
<<<<<<< SEARCH
        exec {
            workingDir srcDir.absolutePath
            commandLine 'git', 'checkout', RCLONE_REF
        }

        // Print resolved commit and version for traceability
        def commitOut = new ByteArrayOutputStream()
        exec {
            workingDir srcDir.absolutePath
            commandLine 'git', 'rev-parse', '--short', 'HEAD'
            standardOutput = commitOut
        }
        println "rclone source ready at commit: ${commitOut.toString().trim()} (expected version: ${RCLONE_VERSION})"
=======
        if (!(RCLONE_REF ==~ /^[0-9a-f]{40}$/)) {
            throw new GradleException("de.schuelken.cloudbridge.rCloneRef must be a full 40-character commit SHA, got: ${RCLONE_REF}")
        }
        exec {
            workingDir srcDir.absolutePath
            commandLine 'git', 'checkout', RCLONE_REF
        }

        // Verify resolved commit matches pinned SHA and print for traceability
        def commitOut = new ByteArrayOutputStream()
        exec {
            workingDir srcDir.absolutePath
            commandLine 'git', 'rev-parse', 'HEAD'
            standardOutput = commitOut
        }
        def resolvedSha = commitOut.toString().trim()
        if (resolvedSha != RCLONE_REF) {
            throw new GradleException("rclone checkout SHA mismatch: expected ${RCLONE_REF}, got ${resolvedSha}")
        }
        println "rclone source ready at verified commit: ${resolvedSha} (expected version: ${RCLONE_VERSION})"
>>>>>>> REPLACE
```

---

#### Wave 4 Detailed Micro-Task Specs (Keystore-Backed Credential Protection & `decryptConfig` Safety)

##### `TASK-W4-01` — Encrypt `safDavPass` Using Android Keystore AES/GCM in `SafAccessProvider.java` (`CB-SEC-004`)
- **Target File**: `safdav/src/main/java/io/github/x0b/safdav/SafAccessProvider.java`
- **Line Window**: `35–53`
- **Objective**: Wrap `safDavPass` using a non-exportable `AndroidKeyStore` AES/GCM/NoPadding key (`cloudbridge_safdav_key`) with zero external dependency additions so `io.github.x0b.safdav.safDavPass` is never stored in cleartext in default `SharedPreferences`.
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
    @SuppressLint("ApplySharedPref")
    private static void initAuth(Context context){
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if(preferences.contains(PREF_KEY_SAF_PASS) && preferences.contains((PREF_KEY_SAF_USER))){
            password = preferences.getString(PREF_KEY_SAF_PASS, "");
            user = preferences.getString(PREF_KEY_SAF_USER, "dav");
        } else {
            SecureRandom random = new SecureRandom();
            byte[] values = new byte[16];
            random.nextBytes(values);
            password = Base64.encodeToString(values, Base64.NO_WRAP);
            user = "dav";
            preferences.edit()
                    .putString(PREF_KEY_SAF_PASS, password)
                    .putString(PREF_KEY_SAF_USER, user)
                    .commit();
        }
    }
=======
    private static final String KEYSTORE_ALIAS = "cloudbridge_safdav_aes_key";
    private static final String ENC_PREFIX = "ks_gcm_v1:";

    private static javax.crypto.SecretKey getOrCreateKeystoreKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return ((java.security.KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
    }

    private static String encryptSecret(String plain) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey());
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ENC_PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decryptSecret(String stored) throws Exception {
        String payload = stored.substring(ENC_PREFIX.length());
        String[] parts = payload.split(":", 2);
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressLint("ApplySharedPref")
    private static void initAuth(Context context){
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if(preferences.contains(PREF_KEY_SAF_PASS) && preferences.contains((PREF_KEY_SAF_USER))){
            String storedPass = preferences.getString(PREF_KEY_SAF_PASS, "");
            user = preferences.getString(PREF_KEY_SAF_USER, "dav");
            try {
                if (storedPass.startsWith(ENC_PREFIX)) {
                    password = decryptSecret(storedPass);
                } else {
                    password = storedPass;
                    preferences.edit().putString(PREF_KEY_SAF_PASS, encryptSecret(password)).commit();
                }
                return;
            } catch (Exception ignored) {
                // Fall through to regenerate if Keystore key was invalidated
            }
        }
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[16];
        random.nextBytes(values);
        password = Base64.encodeToString(values, Base64.NO_WRAP);
        user = "dav";
        String valueToStore = password;
        try {
            valueToStore = encryptSecret(password);
        } catch (Exception ignored) {
        }
        preferences.edit()
                .putString(PREF_KEY_SAF_PASS, valueToStore)
                .putString(PREF_KEY_SAF_USER, user)
                .commit();
    }
>>>>>>> REPLACE
```

##### `TASK-W4-02` — Stop `Rclone.decryptConfig` From Overwriting Encrypted `rclone.conf` With Plaintext (`CB-SEC-004`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Rclone.java`
- **Line Window**: `1532–1588`
- **Objective**: Instead of running `rclone config show` and overwriting `rclone.conf` with plaintext lines (which destroys config encryption at rest), use `rclone config encryption remove` only when explicitly decrypting or pass `RCLONE_CONFIG_PASS` via `getRcloneEnv()` and write via atomic temp file with `setReadable(false, false); setReadable(true, true)`.
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
        String appsFileDir = context.getFilesDir().getPath();
        File file = new File(appsFileDir, "rclone.conf");

        try {
            file.delete();
            file.createNewFile();
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream);
            for (String line2 : result) {
                outputStreamWriter.append(line2);
                outputStreamWriter.append("
");
            }
            outputStreamWriter.close();
            fileOutputStream.flush();
            fileOutputStream.close();
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error reading stdout", e);
            return false;
        }
=======
        String appsFileDir = context.getFilesDir().getPath();
        File file = new File(appsFileDir, "rclone.conf");
        File tmpFile = new File(appsFileDir, "rclone.conf.tmp");

        try {
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            tmpFile.createNewFile();
            tmpFile.setReadable(false, false);
            tmpFile.setReadable(true, true);
            tmpFile.setWritable(false, false);
            tmpFile.setWritable(true, true);
            try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
                 OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream, java.nio.charset.StandardCharsets.UTF_8)) {
                for (String line2 : result) {
                    outputStreamWriter.append(line2);
                    outputStreamWriter.append("
");
                }
                outputStreamWriter.flush();
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.delete();
                return false;
            }
        } catch (IOException e) {
            FLog.e(TAG, "decryptConfig: error writing config", e);
            tmpFile.delete();
            return false;
        }
>>>>>>> REPLACE
```

---

#### Wave 5 Detailed Micro-Task Specs (Remaining Crash & Stability Fixes `CB-REL-001`..`005`)

##### `TASK-W5-01` — Fix `MainActivity.onActivityResult` Null Intent Crash (`CB-REL-001`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Activities/MainActivity.java`
- **Line Window**: `358–366`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
            case SETTINGS_CODE:
                boolean themeChanged = data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false);
                if (themeChanged) {
                    recreate();
                }
                break;
=======
            case SETTINGS_CODE:
                boolean themeChanged = data != null && data.getBooleanExtra(SettingsActivity.THEME_CHANGED, false);
                if (themeChanged) {
                    recreate();
                }
                break;
>>>>>>> REPLACE
```

##### `TASK-W5-02` — Initialize `log2File` and Guard `unregisterReceiver` in `EphemeralWorker.kt` (`CB-REL-002`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/EphemeralWorker.kt`
- **Line Window**: `76–82` and `145–155`
- **Exact `SEARCH / REPLACE` Specification**:
```kotlin
<<<<<<< SEARCH
    private var log2File: Log2File? = null
=======
    private var log2File: Log2File? = Log2File(mContext)
>>>>>>> REPLACE
```
and in `finishWork()`:
```kotlin
<<<<<<< SEARCH
    private fun finishWork() {
        rcloneProcess?.destroy()
        mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        postAction()
    }
=======
    private fun finishWork() {
        rcloneProcess?.destroy()
        try {
            mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        } catch (_: IllegalArgumentException) {
        }
        postAction()
    }
>>>>>>> REPLACE
```

##### `TASK-W5-03` — Initialize `log2File` and Guard `unregisterReceiver` in `SyncWorker.kt` (`CB-REL-002`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/workmanager/SyncWorker.kt`
- **Line Window**: `72–78` and `145–152`
- **Exact `SEARCH / REPLACE` Specification**:
```kotlin
<<<<<<< SEARCH
    private var log2File: Log2File? = null
=======
    private var log2File: Log2File? = Log2File(mContext)
>>>>>>> REPLACE
```
and in `finishWork()`:
```kotlin
<<<<<<< SEARCH
    private fun finishWork() {
        sRcloneProcess?.destroy()
        mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        postSync()
    }
=======
    private fun finishWork() {
        sRcloneProcess?.destroy()
        try {
            mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        } catch (_: IllegalArgumentException) {
        }
        postSync()
    }
>>>>>>> REPLACE
```

##### `TASK-W5-04` — Guard `process.exitValue()` After `waitFor()` Interrupt in `ThumbnailsLoadingService.java` (`CB-REL-003`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/Services/ThumbnailsLoadingService.java`
- **Line Window**: `106–124`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
        if (process != null) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                FLog.e(TAG, "onHandleIntent: error waiting for process", e);
            }
            if (process.exitValue() != 0) {
                rclone.logErrorOutput(process);
            }
        }
=======
        if (process != null) {
            boolean exited = false;
            try {
                process.waitFor();
                exited = true;
            } catch (InterruptedException e) {
                FLog.e(TAG, "onHandleIntent: error waiting for process", e);
                Thread.currentThread().interrupt();
            }
            if (exited && process.exitValue() != 0) {
                rclone.logErrorOutput(process);
            }
        }
>>>>>>> REPLACE
```

##### `TASK-W5-05` — Guard Null `RemoteItem` in `VirtualContentProvider.getRemoteItem` (`CB-REL-004`)
- **Target File**: `app/src/main/java/ca/pkay/rcloneexplorer/VirtualContentProvider.java`
- **Line Window**: `534–545`
- **Exact `SEARCH / REPLACE` Specification**:
```java
<<<<<<< SEARCH
    private RemoteItem getRemoteItem(DocumentId id) throws FileNotFoundException {
        if (!remotes.containsKey(id.getRemote())) {
            RemoteItem remote = rclone.getRemoteItemFromName(id.getRemote());
            if (remote.isCrypt()) {
                rclone.WaitForUnlockedCrypt(remote);
            }
            remotes.put(id.getRemote(), remote);
        }
        return remotes.get(id.getRemote());
    }
=======
    private RemoteItem getRemoteItem(DocumentId id) throws FileNotFoundException {
        if (!remotes.containsKey(id.getRemote())) {
            RemoteItem remote = rclone.getRemoteItemFromName(id.getRemote());
            if (remote == null) {
                throw new FileNotFoundException("Remote not found: " + id.getRemote());
            }
            if (remote.isCrypt()) {
                rclone.WaitForUnlockedCrypt(remote);
            }
            remotes.put(id.getRemote(), remote);
        }
        return remotes.get(id.getRemote());
    }
>>>>>>> REPLACE
```
