# Trailwatch Hiker Phone

This is the first Android app foundation for my Computer Engineering thesis project, Trailwatch. It is for the hiker's phone. Right now it records GPS practice data offline so I can test the phone side before the ESP32 parts and firmware are ready.

## What works in this starter build

- Enter an assigned hiker node ID.
- Start and stop GPS tracking. Android keeps a tracking notification visible while tracking continues in the background.
- Save latitude, longitude, GPS accuracy, satellites used in the fix, phone battery level, and UTC capture time to app-private JSON Lines storage.
- Export the local GPS log with Android's file picker.
- Work without a map, internet connection, account, or server.

The exported JSON Lines file is a local app record. It is **not** the Trailwatch LoRa uplink, a Bluetooth message, or a USB serial format.

## Build it

Open this folder in Android Studio, let Android Studio use its configured Android SDK, then build and run the `app` configuration on an Android phone. The project uses Java and the Android SDK without a third-party app library. This computer did not have Android Studio, Gradle, Android SDK environment variables, or ADB available when this starter was created, so I could not build or install the APK here.

For a real phone test, grant precise location, enable the phone's Location setting, go outside for a clear GPS view, and start a practice session. The first fix can take time. Export a log to inspect the locally captured values. Check how Android/MIUI and the phone's battery settings affect background tracking before relying on it during a hike.

## ESP32 connection status

The app deliberately does not connect to Bluetooth or USB yet. The project still needs a firmware-agreed phone-to-ESP32 transport and message contract. `Esp32Transport.java` is only an interface boundary; there is no implementation and no data is sent from the app to hardware or the dashboard.

The GPS service currently samples the Android GPS provider at a 1-second local capture interval. That interval is for local logging only. It does not define an uplink interval, packet format, or radio timing.

## Local data

The app stores `gps-samples.jsonl` in Android app-private storage. Use **Export my GPS log** to copy it to a location you choose. Uninstalling the app clears its private files. The export action makes a user-selected copy; it does not upload the file.
