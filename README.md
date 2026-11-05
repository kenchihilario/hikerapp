# Trailwatch Hiker Phone

This is the Android hiker-phone app for my Computer Engineering thesis project, Trailwatch. It is being developed alongside the dashboard. It stays offline and stores its practice log on the phone.

## App features now in code

- Enter and save a hiker node ID.
- Choose the planned phone-to-board design: Bluetooth LE or USB-C OTG. The choice is only recorded; it does not connect to a board yet.
- Capture GPS in a foreground service, including coordinates, accuracy, satellites used in the fix, phone battery percentage, timestamp, and the current logical safety flags.
- Warn when phone battery falls below 20%.
- Hold the SOS control for 2 seconds. The app sets the emergency flag and logs the event. Without a real ESP32 adapter, it clearly says **SOS NOT SENT**; use the normal emergency procedure.
- Show and alert on logical `ALERT` (displayed as `OFF TRAIL - TURN BACK`), `STORM - TURN BACK`, and `SOS RECEIVED` messages when delivered by a board adapter.
- Mark `LINK LOST` after three consecutive reply slots are reported unanswered by the adapter. It gives a short vibration. The app defines no reply-window timer; the firmware/adapter reports each slot result.
- Run an inactivity check: after 30 minutes without detected movement, show `ARE YOU OK?`; if there is no response in 15 seconds, set the emergency flag. The movement threshold must be entered in meters because the project requirements do not specify one. Leaving it blank disables this check.
- TEST / LIVE mode switch: TEST is on by default and marks records TEST. LIVE mode is clearly labelled as local-only until the ESP32 adapter is available; changing to LIVE does not transmit GPS or SOS data.
- A TEST MODE-only simulator exercises OFF TRAIL, STORM TURN BACK, SOS RECEIVED, three unanswered replies, reply recovery, low battery, and the 15-second inactivity response without contacting a board.
- Save GPS fixes and safety events as JSON Lines in app-private storage, then export the combined log through Android's file picker.

The JSONL file is a local app record. It is **not** the LoRa packet, BLE payload, or USB serial framing.

## Hardware boundary

`Esp32Transport` is the interface for a future approved adapter. It passes logical GPS observations, flags, reply-slot outcomes, and decoded downlinks. There is no BLE/USB implementation, no direct dashboard API, and no data transmission today. The firmware team still needs to settle the phone-to-board service/characteristics or USB interface and message contract. No packet sizes, bytes, UUIDs, line endings, checksums, or serial framing have been invented.

The dashboard proposal routes hiker data through the ESP32/LoRa system to Base Camp and the laptop. This app does not send directly to the dashboard.

## Build in Android Studio

The project is set to Android Gradle Plugin 9.5, Gradle 9.6, compile SDK 37, and target SDK 35. `assembleDebug` completed successfully on this computer. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

To try the app without physical hardware, open it in Android Studio, select the **Medium Phone API 37** emulator, and press **Run**. Leave TEST MODE on and use the **Hardware-free Simulation** panel to exercise alerts. Emulator GPS can be set from the emulator's Extended Controls. A successful APK build does not verify a real phone GPS or ESP32 communication.

To exercise GPS, grant precise location, enable Location, and start tracking outdoors. A movement threshold is needed to enable inactivity checks. Battery saver and manufacturer background restrictions may affect tracking; test them on each target phone.

## Local data and privacy

The app stores `gps-samples.jsonl` in Android app-private storage. GPS rows include a `flags` object; safety rows use `record_type: safety_event`. Export copies the log to a location you choose. The app has no Internet permission and does not upload these records.
