# Trailwatch Hiker UI redesign report

**Date:** 2026-11-05  
**Branch:** `codex/ui-redesign`  
**Scope:** Android home-screen presentation only. Existing unrelated working-tree edits were preserved.

## Changed files

- `app/src/main/java/ph/trailwatch/hikerphone/MainActivity.java` — rebuilt the home screen layout with a scrollable content area, fixed action dock, TEST/LIVE selector, GPS status card, validation and error cards, compact utility row, and simulator dialog. Existing tracking, export, simulator actions, mode preference and SOS hold behavior remain wired to existing methods.
- `app/src/main/res/values/colors.xml` and `dimens.xml` — added the requested forest-night colors, spacing, radii and control sizes.
- `app/src/main/res/values/styles.xml` — added reusable text, card, field and button styles, and a dark alert-dialog theme.
- `app/src/main/res/drawable/*.xml` — local shape resources and vector icons for cards, fields, controls, status and utilities.
- `docs/PROJECT_STATUS.md` — updated the handoff status for this UI task.
- `docs/UI_REDESIGN_REPORT.md` — this report.

## Design coverage

| Design item | Status | Notes |
|---|---|---|
| Forest-night palette, bordered cards, reusable resources | Implemented | Tokens live in `colors.xml`, `dimens.xml`, and `styles.xml`; no gradients or elevation effects were added. |
| Header and TEST/LIVE segmented control | Implemented | Existing confirmation dialog is retained; the selector is disabled during tracking. |
| Honest radio/LIVE connection strip | Implemented | States that radio is not set up and that LIVE is local-only without ESP32 delivery. |
| GPS status, loading spinner/skeletons and three metrics | Implemented | Fix, record count, and TEST/REAL label are the only metrics. |
| Node ID validation and three setup fields | Implemented | Inline error text and red field outline; planning-only board selector remains. |
| Simulator moved off the main page | Implemented | TEST-only dialog retains the existing eight simulator actions. |
| Pinned tracking/SOS dock and offline footer | Implemented | Tracking and two-second SOS controls remain present at the bottom. |
| Location and SOS error states | Implemented | Location error offers app settings; SOS says it was saved locally and not sent. |
| Fit all content without scrolling at 390×844dp | Partial | The screen is scrollable as required for small screens. Emulator inspection at about 411×914dp showed the fixed dock partially covers the utility row until the page is scrolled. Smaller devices and larger font scales need further visual tuning. |
| Dark confirmation dialog and TEST/LIVE selection | Implemented and emulator-checked | Final APK showed the dark dialog; TEST remained selected while it was open, LIVE selected after confirmation, and the emulator was returned to TEST. |

The UI is still built programmatically because the existing screen was programmatic. Shared appearance moved into Android resources. No offline font files were available, so it uses system `sans-serif` and `sans-serif-medium` fallbacks.

## Constraints and behavior

- `app/src/main/AndroidManifest.xml` and `app/build.gradle` are unchanged. No permission, Internet access, HTTP client, library or dependency was added.
- No JSONL serialization edits were made for this UI task; the existing `test_mode` key remains in use. The working tree already had unrelated uncommitted changes in `GpsSample.java` and `LocalSampleStore.java` from earlier work, so this report does not claim the full branch diff against `HEAD` is format-neutral.
- `Esp32Transport` and serial/BLE boundaries were not changed. No UUID, packet bytes or USB framing were added.
- Existing hiker ID validation, foreground tracking service, permission flow, SOS hold, inactivity logic, simulator behavior and document-picker export remain in place.
- LIVE remains a local phone mode; it does not upload or contact the dashboard. The warning text says this explicitly.
- The design specifies a “LIVE disabled while tracking” control. The implementation keeps the selector disabled while tracking; it does not change the underlying mode behavior.

## Verification

- Ran `& '.\gradlew.bat' --no-daemon assembleDebug --console=plain` after the final source/resource edits: **BUILD SUCCESSFUL**, 33 actionable tasks (12 executed, 21 up-to-date). Gradle reported deprecation warnings; Java compilation noted deprecated API use in `MainActivity.java`.
- `git diff --check` reported no whitespace errors; it printed only line-ending conversion warnings for existing modified files.
- Installed the final APK on `emulator-5554`, inspected the main screen and confirmation dialog, confirmed TEST stays selected while the confirmation is open and LIVE is selected after accepting, then returned the emulator to TEST. The emulator at 411×914dp still clips the utility row behind the fixed dock until the content is scrolled.
- No automated UI tests, physical-phone tests, GPS permission denial tests, large-font tests, SOS hold progress tests, or device-specific background tracking tests were run.

The working tree also contained pre-existing uncommitted app and README edits when this redesign began. They were preserved and are not included in the redesign file list above: `README.md`, `AlertFeedback.java`, `GpsSample.java`, `HikerSafetyState.java`, `InactivityMonitor.java`, `LocalSampleStore.java`, and `LocationTrackingService.java`.

## Manual checks still needed

Check SOS hold/progress and release reset, all simulator actions, location denied/off state and Settings action, tracking while scrolling, 390×844dp, and large system font sizes. Confirm the utility row is reachable by scrolling past the pinned dock on short screens. No physical phone or GPS field test was run.
