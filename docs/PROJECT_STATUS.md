# Trailwatch Hiker Phone App — Project Status

**Reviewed:** 2026-11-05  
**Project:** `D:\Projects for PC\HikerPhoneApp`  
**Branch:** `codex/ui-redesign` (uncommitted working tree; no commit made)  
**Scope:** Android hiker phone application only. The UI was inspected on an emulator earlier in this task and the final sources were build-checked. No physical phone, GPS field use, ESP32, LoRa or dashboard integration is claimed.

## UI redesign handoff — 2026-11-05

- Current branch: `codex/ui-redesign` (no commit made).
- The dark forest home screen redesign is in `app/src/main/java/ph/trailwatch/hikerphone/MainActivity.java` with reusable color, dimension, style and drawable resources. Detailed change and verification notes are in `docs/UI_REDESIGN_REPORT.md`.
- Final `assembleDebug` passed after the last code and resource edits. The final APK was installed on `emulator-5554`; the dark confirmation dialog and TEST-to-LIVE selection were visually checked, and the emulator was returned to TEST.
- The page uses a scroll view and pinned dock. Emulator inspection showed the utility row partly behind the dock at the tested viewport; smaller screen and larger-font fit remain to be checked and tuned.
- This is presentation work; no permission, Gradle dependency, preference key or `Esp32Transport` change was made. Pre-existing uncommitted edits already existed in GPS/log model files; see the redesign report for that scope caveat.
- The detailed status sections below were written before the UI rewrite. Their `MainActivity.java` line references are historical and should be rechecked against the current file; use `docs/UI_REDESIGN_REPORT.md` for current UI evidence.

## Handoff snapshot — 2026-11-05

- `assembleDebug` completed successfully after the home screen redesign. The current APK is at `app/build/outputs/apk/debug/app-debug.apk`.
- TEST is the default. LIVE is a persistent app preference represented by turning off the existing `test_mode` flag. Selecting LIVE asks for confirmation, hides the simulator and shows a local-only warning (`app/src/main/java/ph/trailwatch/hikerphone/MainActivity.java:156-178,625-637,950-976`).
- LIVE is **not** a hardware connection or a separate database/network profile. The app has no ESP32 adapter implementation or dashboard upload. Phone GPS observations and SOS events remain local (`README.md:21-25`; `AndroidManifest.xml:1-28`).
- The app’s logical adapter interface deliberately leaves BLE services/characteristics, USB serial framing and message serialization undefined (`Esp32Transport.java:3-20`). No packet sizes, bytes, UUIDs or framing were added.
- The project has no checked-in automated UI test directory or UI test files. Emulator inspection confirmed the home screen and TEST/LIVE confirmation rendered, but the most recent dialog-theme change was only compile-checked. See `docs/UI_REDESIGN_REPORT.md` for exact limits and remaining visual checks.

## Status labels

- **IMPLEMENTED AND TESTED** — present and exercised by a passing build or automated test in this session.
- **IMPLEMENTED BUT UNTESTED** — code exists, but a test was not found or was not run for that behavior.
- **PARTIAL** — some behavior exists but has a known limitation or missing element.
- **NOT IMPLEMENTED** — not found in the project.
- **BLOCKED (waiting on hardware/firmware/site data)** — needs hardware, firmware specification, or field decisions/data.

## 1. Overview

**IMPLEMENTED AND TESTED — Android build foundation.** This is a native Android application written in Java, using the Android SDK and Gradle. It has no third-party app library declared in the app module. Gradle uses Android Gradle Plugin 9.5.0, Gradle 9.6.0, compile SDK 37, target SDK 35, and min SDK 26 (`build.gradle:1-3`; `app/build.gradle:1-16`; `gradle/wrapper/gradle-wrapper.properties:1-9`). The debug APK build passed with `33 actionable tasks` and `BUILD SUCCESSFUL`; Gradle reported deprecated-feature warnings, not build errors.

**PARTIAL — current LIVE mode meaning.** The visible TEST/LIVE selector persists through the existing `test_mode` preference. LIVE means “TEST flag off” for locally recorded observations. It does not select a server/database or transmit data. The UI explicitly says “LIVE mode · local GPS only. ESP32 not connected. Nothing sent to Base Camp” when there is no adapter (`MainActivity.java:625-637,950-976`).

### Important project files

| Path | Purpose |
|---|---|
| `app/src/main/java/ph/trailwatch/hikerphone/MainActivity.java` | Main UI, TEST/LIVE selector, GPS/SOS controls, hardware-free simulator, JSONL export. |
| `app/src/main/java/ph/trailwatch/hikerphone/LocationTrackingService.java` | Foreground GPS service, local sample/event storage, phone battery and inactivity handling, future transport injection point. |
| `app/src/main/java/ph/trailwatch/hikerphone/GpsSample.java` | Logical local GPS observation and JSON representation. |
| `app/src/main/java/ph/trailwatch/hikerphone/LocalSampleStore.java` | App-private JSON Lines append/read-count/file access. |
| `app/src/main/java/ph/trailwatch/hikerphone/HikerSafetyState.java` | Logical safety flags, downlink reactions, battery and reply-slot state. |
| `app/src/main/java/ph/trailwatch/hikerphone/HikerFlags.java` | Logical flags; does not define wire encoding. |
| `app/src/main/java/ph/trailwatch/hikerphone/HikerDownlink.java` | Logical reply types and fields for a future approved adapter. |
| `app/src/main/java/ph/trailwatch/hikerphone/Esp32Transport.java` | Interface for a future board adapter; no concrete BLE/USB implementation. |
| `app/src/main/java/ph/trailwatch/hikerphone/InactivityMonitor.java` | Stillness and response timeout state machine. |
| `app/src/main/java/ph/trailwatch/hikerphone/AlertFeedback.java` | Local tone and vibration behavior. |
| `app/src/main/AndroidManifest.xml` | Android permissions, app entry point and location foreground service. No Internet permission is declared. |
| `app/build.gradle`, `build.gradle`, `settings.gradle` | Android SDK/plugin configuration and single app module. |
| `README.md` | Feature, hardware boundary, build and local-data notes. |
| `docs/PROJECT_STATUS.md` | This implementation and handoff report. |

## 2. Architecture and behavior

**IMPLEMENTED BUT UNTESTED — GPS capture and local persistence.** The activity validates the hiker ID and requests Android location permissions, then starts the foreground location service (`MainActivity.java:286-311,600-616`). The service receives GPS locations, reads satellite and phone battery values, builds a `GpsSample`, appends it to local JSONL, updates the screen, and advances the inactivity monitor (`LocationTrackingService.java:241-268`). The file is `gps-samples.jsonl` in app-private storage; each record is one JSON object per line (`LocalSampleStore.java:11-29`).

**IMPLEMENTED BUT UNTESTED — phone safety behavior.** `HikerSafetyState` tracks emergency pending, low battery, turn-back receipt, link-lost, and test flags (`HikerSafetyState.java:9-17,38-91`). Three adapter-reported unanswered reply slots trigger LINK LOST; an acknowledged slot clears it (`HikerSafetyState.java:38-52`). Phone battery below 20% creates a low-battery event (`HikerSafetyState.java:54-61`). Logical downlinks support acknowledgement, alert, SOS received and storm turn-back (`HikerDownlink.java:3-17`; `HikerSafetyState.java:70-87`). This behavior has not been verified with decoded messages from firmware.

**IMPLEMENTED BUT UNTESTED — inactivity prompt.** Stillness is measured using a positive, operator-entered movement threshold. After 30 minutes without threshold movement, the app prompts; after 15 seconds without an answer, the service sets emergency pending (`InactivityMonitor.java:5-24,26-65`; `LocationTrackingService.java:274-285`). The threshold is not provided by project requirements; leaving it blank disables the check (`MainActivity.java:189-200,313-326`).

**PARTIAL — SOS.** Holding SOS for two seconds validates and saves the hiker ID and sets the local emergency flag. Without a connected adapter, the app records SOS locally and displays that it was not sent, with instructions to use the normal emergency procedure (`MainActivity.java:244-284`). The currently exposed service method sets the emergency state and persists flags; successful radio delivery is not implemented (`LocationTrackingService.java:153-157`).

**IMPLEMENTED BUT UNTESTED — hardware-free simulator.** TEST-only controls simulate off-trail alert, storm turn-back, SOS received, missed/recovered replies, low battery and inactivity countdown. Events are marked SIMULATION and stay separate from the phone service state (`MainActivity.java:209-220,499-552`). They do not test radio, GPS geofencing, firmware or Base Camp delivery.

## 3. Modes and data

**IMPLEMENTED BUT UNTESTED — TEST/LIVE selector.** TEST starts enabled by default (`MainActivity.java:126`; `HikerSafetyState.java:16`). Switching to LIVE prompts the operator, turns off the `test_mode` preference and hides the simulator; switching back restores TEST (`MainActivity.java:131-145,441-474`). Mode selection is disabled while GPS tracking is active (`MainActivity.java:457-472`). The selected value persists in Android SharedPreferences via `test_mode`; there is not a separate live profile or mode database.

**PARTIAL — source labels.** GPS records use source `TEST` when the test flag is true and `REAL` when false (`GpsSample.java:28-49`). Safety events use the same TEST/REAL convention, while simulated events explicitly use `SIMULATION` (`LocationTrackingService.java:319-330`; `MainActivity.java:510-515,555-572`). In LIVE mode, REAL means TEST is off for a locally captured phone record; it does **not** prove transmission, Base Camp receipt, an actual trail session or research eligibility. Treat exported data accordingly.

**IMPLEMENTED BUT UNTESTED — local export.** The activity exports the combined GPS/safety JSONL file using Android’s document picker (`MainActivity.java:574-598`). It does not upload the file. The app declares location, foreground-location, notification and vibration permissions; no Internet permission is present (`AndroidManifest.xml:1-28`). The manifest also disables Android backup (`AndroidManifest.xml:11-15`).

## 4. Hardware and dashboard integration

**NOT IMPLEMENTED — BLE/USB communication.** The UI allows choosing a planned BLE or USB-C OTG design, but only saves that selection; hardware status remains not connected until a concrete service is added (`MainActivity.java:156-180`; `README.md:21-25`). `Esp32Transport` defines logical method signatures and expressly does not define UUIDs, serialization, bytes, line endings or USB framing (`Esp32Transport.java:3-20`).

**NOT IMPLEMENTED — dashboard/API upload.** There is no app HTTP client and no Internet permission. The intended data path remains phone → ESP32/LoRa → Base Camp/dashboard, but no phone-to-board sending is currently available (`README.md:21-25`; `AndroidManifest.xml:1-28`).

**BLOCKED (waiting on hardware/firmware/site data) — real link.** Implementing and validating the adapter depends on the approved phone-to-board design and firmware-owned message contract. No radio, BLE or USB communication is claimed or tested.

## 5. Build and verification

**IMPLEMENTED AND TESTED — debug APK compilation.** Command run from the project root:

```powershell
& '.\gradlew.bat' --no-daemon assembleDebug --console=plain
```

Result: `BUILD SUCCESSFUL`; 33 actionable tasks. Output artifact: `app/build/outputs/apk/debug/app-debug.apk`. The compiler noted some deprecated Android APIs; Gradle warned that deprecated features may not work with Gradle 10.

**NOT IMPLEMENTED — automated test suite.** A project file scan found no test files or test directory. No unit, instrumentation, emulator, or physical-phone tests were run in this session. The successful build verifies compilation and APK packaging only, not runtime behavior.

**IMPLEMENTED BUT UNTESTED — emulator instructions.** README says to open the project in Android Studio, select the Medium Phone API 37 emulator, and Run (`README.md:27-33`). No emulator launch was performed during this review.

## 6. Known issues and risks

1. **High — LIVE could be mistaken for connected/live telemetry.** The app labels LIVE as selected but has no adapter or upload. Keep the visible “local GPS only / not connected” notice and do not treat the log as received by Base Camp (`MainActivity.java:457-474`; `Esp32Transport.java:3-20`).
2. **High — no automated/runtime tests.** The current evidence is a successful Gradle build only; alert timers, permissions, background service behavior and UI transitions still need emulator/device verification.
3. **High — hardware protocol unresolved.** BLE characteristics or USB interface and the logical-to-firmware mapping remain unspecified. Do not invent packet bytes, serial framing or BLE UUIDs (`Esp32Transport.java:3-20`).
4. **Medium — movement threshold is operator supplied.** Inactivity logic is disabled until a positive threshold is entered; the threshold value itself is not prescribed (`InactivityMonitor.java:19-24`; `MainActivity.java:313-326`).
5. **Medium — mode uses `test_mode`, not a distinct environment profile.** LIVE turns off the TEST flag; it does not switch database, node configuration, or destination (`MainActivity.java:447-474`). This can make local records appear as REAL in exports even without transmission (`GpsSample.java:28-49`).
6. **Low — deprecated Android/Gradle APIs.** Build succeeds, but deprecation warnings should be reviewed before upgrading Gradle/Android tooling.

## 7. Not finished / blocked

**BLOCKED (waiting on hardware/firmware/site data)**

- Concrete ESP32 transport for the agreed BLE or USB-C OTG design.
- Firmware-approved mapping of app GPS, flags and SOS into the phone-to-board logical interface.
- Actual decoded downlink delivery and reply-slot results from firmware.
- Physical phone GPS, vibration, audio, notification and background/battery behavior on target devices.
- End-to-end data delivery through ESP32/LoRa to the dashboard and validation that records are logged correctly.
- Field validation of inactivity distance threshold and all alert behaviors.

## 8. Suggested next steps

1. Run the APK in the API 37 emulator and verify TEST/LIVE confirmation, simulator visibility, GPS permission flow, local log and export.
2. Decide whether LIVE should remain a local-recording state or become selectable only after a verified adapter connects; keep the warning visible until then.
3. Agree the actual ESP32 phone interface and logical message mapping with the firmware owner; then implement the adapter without guessing packet/framing details.
4. Add unit tests for `HikerSafetyState` and `InactivityMonitor`, plus Android UI/instrumentation checks if practical.
5. Test the installed APK on the target phone models for location, notification, sound/vibration, background execution and battery restrictions.
6. Only after the link is implemented, run end-to-end tests for GPS/SOS transmission, received SOS/turn-back messages, and dashboard observation.

## One-page status summary

| Area | Status | Note |
|---|---|---|
| Android project and debug build | **IMPLEMENTED AND TESTED** | APK assembled successfully; runtime not exercised. |
| Local GPS capture and JSONL storage | **IMPLEMENTED BUT UNTESTED** | Foreground service stores local observations. |
| TEST/LIVE mode selector | **IMPLEMENTED BUT UNTESTED** | Persistent flag switch; LIVE is still local-only. |
| Hardware-free safety simulator | **IMPLEMENTED BUT UNTESTED** | Local simulation controls; no ESP32 traffic. |
| Phone safety state and inactivity checks | **IMPLEMENTED BUT UNTESTED** | Logical behaviors exist; no automated tests or device verification. |
| SOS delivery to Base Camp | **NOT IMPLEMENTED** | Local SOS pending only; explicitly reports not sent. |
| BLE/USB-C ESP32 adapter | **BLOCKED (waiting on hardware/firmware/site data)** | Interface and firmware contract must be agreed. |
| Dashboard upload / end-to-end path | **NOT IMPLEMENTED** | No direct app upload; expected path passes through ESP32/LoRa. |
| Automated app tests | **NOT IMPLEMENTED** | No test files found; build is the only verification. |
| Offline/privacy baseline | **IMPLEMENTED BUT UNTESTED** | No Internet permission; records remain app-private unless exported. |
