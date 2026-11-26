# CloudBridge Material 3 Expressive implementation plan

**Status** — Planned. This is a documentation-only handoff for the CloudBridge Android app (`de.schuelken.cloudbridge`) in the expressive worktree. Resolve the file and activity names mentioned below against the checkout during Phase 0.

Read `AGENTS.md` first and follow all project rules strictly.

## Hard Guardrails (Do Not Violate)
1. **Android Views + XML only.** Do NOT add Jetpack Compose, do NOT change `compileSdk`/`targetSdk`/`minSdk`, and do NOT upgrade dependencies in `app/build.gradle`.
2. **Do NOT touch `rclone/`**, `gradle.properties` (`rCloneRef`/`rCloneRepoUrl`), transfer/sync logic, provider option semantics, or localized `res/values-*/strings.xml` files. Only edit base `res/values/strings.xml` if strictly required.
3. **Target flavor:** Always verify using the `oss` debug variant (`assembleOssDebug`, `testOssDebugUnitTest`, `lint -x :rclone:buildAll`).
4. **Never rename or drop View IDs** without updating every Java/Kotlin caller (`findViewById`, ViewBinding, adapters) and every `layout-sw720dp/` variant in the same step.
5. **Work in strict phase order.** After EACH phase, run:
   `./gradlew lint -x :rclone:buildAll && ./gradlew testOssDebugUnitTest && ./gradlew assembleOssDebug`
   Fix any errors before moving to the next phase. If the same build/lint error fails twice, stop and report the exact error instead of guessing.

---

## Phase 0: Baseline Inspection (Read-Only First)
1. Run `git status`, `git diff --stat`, and check `git log -n 5 --oneline` to see what partial UI edits already exist in this worktree.
2. Inspect `app/build.gradle`, `res/values/themes.xml`, `res/values-night/themes.xml`, `res/values-v31/`, and `res/values-night-v31/`.
3. List all modified layouts and verify whether matching `layout-sw720dp/` files exist and whether all View IDs match their Java/Kotlin Activity/Fragment/Adapter files.
4. Summarize what is already done vs. missing before making your first edit.

---

## Phase 1: Theme, Dynamic Color, Edge-to-Edge Insets, and Drawer
1. **Theme & Dynamic Color (`themes.xml`, `styles.xml`, Application/Activity theme helper):**
   - Trace how Follow System, Light, Dark, and AMOLED Black are resolved at app startup and on Activity recreation.
   - Enable Material 3 `DynamicColors` on API 31+ where appropriate, **but** apply the AMOLED Black `ThemeOverlay` *after* `DynamicColors` so AMOLED keeps `#000000` `android:colorBackground` / `colorSurface` while using `surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh` with visible contrast for cards, menus, and dialogs.
   - Replace clashing hardcoded dark colors in `res/values*/colors.xml` with Material 3 semantic roles (`?attr/colorSurface`, `?attr/colorSurfaceContainer`, `?attr/colorSurfaceContainerLow`, `?attr/colorSurfaceContainerHigh`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`). Keep `?attr/colorError` for destructive actions.
2. **Edge-to-Edge & Insets:**
   - Remove `android:windowOptOutEdgeToEdgeEnforcement` only after verifying `enableEdgeToEdge()` and `ViewCompat.setOnApplyWindowInsetsListener` handle `statusBars()`, `navigationBars()`, `displayCutout()`, and `ime()` across `MainActivity`, `SettingsActivity`, `TaskActivity`, `FilterActivity`, `TriggerActivity`, `RemoteConfigActivity`, `AboutActivity`, `ChangelogActivity`, `FilePickerActivity`, and `SharingActivity`.
   - Ensure no screen has duplicate top/bottom padding and no bottom button or last list item is covered by the gesture bar or keyboard.
3. **Navigation Drawer (`activity_main.xml`, `nav_header_main.xml`, `app_bar_main.xml`, `layout-sw720dp/app_bar_main.xml`):**
   - Keep the sidebar, pinned remotes, and `Remotes` toolbar search/menu behavior.
   - Style the drawer container (`?attr/colorSurfaceContainerLow`), `28dp` trailing corners, active pill indicator, and `Pinned` header spacing consistently with the active theme.
4. **Run Phase 1 verification:** `lint -x :rclone:buildAll`, `testOssDebugUnitTest`, `assembleOssDebug`.

---

## Phase 2: Editors (`TaskActivity`, `FilterActivity`, `TriggerActivity`, `RemoteConfigActivity`)
1. **Move floating `Save` and `Next` buttons out of the scroll area:**
   - Replace floating `Save`/`Next` FABs that overlap form fields or provider rows with either a TopAppBar action button (pill `MaterialButton` / action item) or a non-overlapping docked bottom bar that sits above `navigationBars()` + `ime()` insets.
   - Apply the same pattern to `layout-sw720dp/remote_config_form.xml` and other wide variants.
2. **Replace `Spinner` controls with Material 3 Exposed Dropdowns (where appropriate):**
   - Use `com.google.android.material.textfield.TextInputLayout` with `Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu` and `MaterialAutoCompleteTextView` (`android:inputType="none"`).
   - **CRITICAL:** Always call `autoCompleteTextView.setText(displayLabel, false)` (pass `false`!) so existing options are not filtered out when reopening a saved record.
   - Keep persisted internal IDs/values separate from user-facing labels, and preserve selection state across rotation and dependent visibility changes (e.g., `Direction` showing/hiding local vs. remote paths).
3. **Form layout & validation:**
   - Replace heavy thick-bordered nested cards with clean `?attr/colorPrimary` section headers (`textAppearanceTitleSmall`) and lighter `?attr/colorSurfaceContainerLow` sections.
   - Ensure `FilterActivity` shows an accurate Edit vs. Create title, `TriggerActivity` weekday chips/toggles remain usable at large font sizes, and `RemoteConfigActivity` preserves provider search, validation errors, credential masking, and OAuth routes.
4. **Run Phase 2 verification:** `lint -x :rclone:buildAll`, `testOssDebugUnitTest`, `assembleOssDebug`.

---

## Phase 3: Settings, Reset Dialog, and Documentation Screens
1. **Settings (`settings_fragment.xml` and preference sub-screens):**
   - Group the Settings landing rows into clear sections using `MaterialCardView` (`?attr/colorSurfaceContainer`, `20dp` corner radius) with monochrome/tonal `?attr/colorOnSurfaceVariant` icons and Material 3 typography.
   - Keep all existing preference keys, callbacks, permission handoffs (`File Access`), and version-specific toggles intact.
   - Ensure theme, concurrency, and custom preference dialogs use `MaterialAlertDialogBuilder` with `?attr/colorSurfaceContainerHigh` and `28dp` corners.
2. **Destructive Reset Dialog:**
   - Fix the low-contrast black trash icon: tint it with `?attr/colorError`.
   - Style `Delete` as a clearly distinguishable destructive button (filled/tonal `?attr/colorError` / `?attr/colorOnError` or `?attr/colorErrorContainer`) next to a `TextButton` `Cancel`. Keep the existing reset handler intact.
3. **Changelog, License, About, Contributors:**
   - Theme `activity_changelog.xml`, `activity_about.xml`, `activity_about_libs.xml`, `activity_contributors.xml` (and `sw720dp` variants) using `?attr/colorSurface` / `?attr/colorOnSurface` so Light, Dark, and AMOLED all wrap text cleanly without horizontal scrolling or invisible text.
4. **Run Phase 3 verification:** `lint -x :rclone:buildAll`, `testOssDebugUnitTest`, `assembleOssDebug`.

---

## Phase 4: Lists, Cards, Overflow Menus, and Secondary Screens
1. **Remote & Task Cards (`fragment_remotes_item.xml`, `fragment_tasks_item.xml`):**
   - Use `?attr/colorSurfaceContainer` with `16dp–20dp` corners.
   - Remote cards: tonal icon container (`?attr/colorSecondaryContainer`), legible wrapping name, visible pinned indicator, and an independent `>= 48dp x 48dp` overflow menu button. Keep the `+` FAB above the gesture bar inset.
   - Task cards: readable title, tonal `Copy`/`Sync`/`Bisync` badge/chip, and clearly labeled wrapping source/destination paths that handle missing/renamed remotes cleanly without stale recycled-view labels.
2. **Overflow Menus:**
   - Apply a consistent `Widget.Material3.PopupMenu` style (`?attr/colorSurfaceContainerHigh`, `16dp` corners) across remote, task, trigger, filter, file, and toolbar menus. Group destructive actions (`Delete`) with a menu group divider. Do NOT use restricted private APIs that fail Android Lint.
3. **Secondary Screens:**
   - Check Explorer (`fragment_file_explorer_*.xml`), Folder Picker (`activity_file_picker.xml`), and Logs (`fragment_logs.xml`) for consistent surface colors, insets, and empty states.
4. **Run Phase 4 verification:** `lint -x :rclone:buildAll`, `testOssDebugUnitTest`, `assembleOssDebug`.

---

## Phase 5: Final Report
After all 4 phases pass Gradle checks:
1. Output `git diff --stat`, APK path + SHA-256 of `app/build/outputs/apk/oss/debug/*.apk`, and the exact output of the three Gradle commands.
2. If an Android 17 device/emulator is connected via `adb devices`, install the APK and run any automated UI/inset checks you can on disposable local test folders, reporting PASS/FAIL/BLOCKED per case A1–A10.
3. If no device/emulator is connected, mark A1–A10 as `BLOCKED (no adb device attached)` and list the exact manual checks remaining for the user.

## Harness-independent acceptance matrix

A person or any automation runner can execute these cases. Navigate by visible label or stable accessibility identity. Where layout geometry matters, compare interactive bounds or verify on-device reachability as well as taking screenshots. Derive any coordinate input from the current layout. Log each case as pass, fail, or blocked with the build, configuration, evidence, and a short explanation.

| Case | Setup and action | Required evidence and result |
| --- | --- | --- |
| A1 · Baseline upgrade | Install the candidate as an update over an audited debug build with disposable remotes, tasks, filters, triggers and preferences. Relaunch. | All test records and values are present. Existing navigation and pinned remotes still work. |
| A2 · Theme matrix | On Android 17, walk Settings, a form, a popup and a document in all four modes. Relaunch after changing the mode. Change OS dark mode in Follow System. Compare two wallpapers if available. | Persisted mode matches selection. Foreground and controls remain legible. The AMOLED canvas is black. Capture each mode and log environmental limits. |
| A3 · Window and back | Run with gesture navigation and three-button navigation. Open drawer, remote form and task editor. Show/hide keyboard, rotate and test Back from dropdown, editor and drawer. | Insets apply once. First/last controls, keyboard dismissal, screen restoration and back stack work. Capture keyboard-visible editor and navigation boundaries. |
| A4 · Settings | Visit every category. Change and restore safe values in General, Look and Feel, Notifications and Logging. Open then cancel Reset. Visit File Access system handoff if available. | Choice and switch persistence, correct Cancel behavior, intact fixtures and reachable permissions. Capture grouped rows and reset confirmation. |
| A5 · Tasks | Create and edit a fixture task. Use Direction, Concurrent transfers, source/target, Filter and Run on Failure. Scroll to the last field. Reopen and verify saved values. Cancel a second edit. | Dropdowns show stored values, Save is clear of every field, errors are visible, Cancel discards changes, and card metadata matches. Capture the last section with Save visible. |
| A6 · Filters and triggers | Create and edit test records, change one rule or weekday, revisit and compare values. Check time picker and cancel paths. Use a disabled trigger or a controlled fixture for scheduling checks. | Correct create/edit titles, visible Save, preserved values and no unintended task execution. |
| A7 · Providers | Search for a provider and select it near the bottom of the list. Try saving a blank Alias, then save a valid fixture and edit it. Revisit advanced options. | Next remains clear of the list, validation is accessible, expected fields and values are retained, and provider-specific limitations are recorded. |
| A8 · Lists and menus | Load long remote/task names and paths. Use pinned drawers, remote overflow and task/filter/trigger menus, plus Explorer search/sort and a folder picker. Tap non-destructive actions and cancel destructive confirmations where present. | Target dimensions and labels, no clipping or obscuration, correct action routing, visible paths, and kept pin state. |
| A9 · Logs and documents | Open About, Changelog, License and Libraries. Review Logs both empty and after a controlled local fixture operation, if available. | Theme matches and documents wrap without losing content. Log entries remain readable and contain only test data in evidence. |
| A10 · Compatibility and accessibility | Repeat representative cases at increased font size, portrait and landscape, a wide layout, and a pre-dynamic-color API supported by the app. Exercise screen reader focus on drawer, dialogs and one editor where available. | No blocked controls or lost labels at supported sizes. Expected fixed-color fallback and discernible focus. Record exact device/API gaps as blocked. |

For fixtures, use local-only test folders and non-sensitive names. Test copy or sync only with disposable input/output, and compare files and log entries before and after. Keep production accounts and data out of the test setup. Verify that no crash or ANR was generated by the test scenarios with app-scoped diagnostic evidence.
