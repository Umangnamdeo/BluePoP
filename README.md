# BluePop

A customizable Bluetooth connection popup for Android. Native Kotlin + Jetpack Compose + Material 3.
When an audio device or wearable connects, disconnects or reconnects, BluePop floats a styled popup
over whatever you are doing, with battery info when the device actually reports it.

Created by Umang Namdeo.

## Status — read this first
This project was written without access to the Android SDK or the internet, so **it has not been
compiled or run on a device yet**. It is written against pinned, known-good versions (AGP 8.5.2,
Kotlin 1.9.24, Compose BOM 2024.06.00). Expect to fix a small number of compile errors on the first
sync; each should be a one-line fix. Test on a real phone — emulators have no Bluetooth.

## Requirements
- Android Studio Koala (2024.1) or newer, JDK 17 (bundled with Studio)
- Android SDK Platform 34
- A physical device on Android 8.0+ (API 26+)

## Open and run
1. `File > Open` and select this `BluePop` folder. Let Gradle sync (it downloads Gradle 8.7 from `gradle/wrapper/gradle-wrapper.properties`).
2. Plug in a phone with USB debugging and press **Run**.
3. On first launch, tap **Grant** on the Home screen for: Bluetooth access, Display over other apps, Notifications.
4. Connect a Bluetooth headset. The popup appears. Use **Test popup** any time without a device.

## Build APKs (command line)
No `gradlew` script is included (it needs a binary jar). Generate it once, with Gradle 8.7+ installed:

    gradle wrapper --gradle-version 8.7

Then:

    ./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk

The release build is minified and signed with the **debug key** so it installs immediately. For
distribution, create your own keystore and replace `signingConfig` in `app/build.gradle.kts`:

    keytool -genkeypair -v -keystore bluepop.jks -alias bluepop -keyalg RSA -keysize 2048 -validity 10000

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## How it works
- `BluePopService` — foreground service (`connectedDevice`). Registers one receiver for
  `ACL_CONNECTED`, `ACL_DISCONNECTED`, battery-level and HFP vendor events. No polling; after a
  connect it re-reads battery 3 times (2.5 s apart) because devices report it late, then idles.
  A foreground service is needed because these broadcasts cannot be received from the manifest.
- `Overlay` — `TYPE_APPLICATION_OVERLAY` window hosting a `ComposeView`. Animations run in a
  `graphicsLayer` block, so they do not recompose per frame. Without the overlay permission, a
  heads-up notification is shown instead.
- `Store` — SharedPreferences JSON exposed as StateFlows (settings, device library, history).
- `Popup.kt` — the seven styles. The Studio preview renders the *same* composables as the overlay.
- Boot: `BootReceiver` restarts the service when "Start after reboot" is on.

## Battery: what is and isn't real
Android has no public API for Bluetooth battery. BluePop tries, in order: the hidden
`getBatteryLevel()`, the left/right/case metadata keys, the `BATTERY_LEVEL_CHANGED` broadcast, and
Apple-style HFP battery reports. Hidden APIs are blocked on some OEM builds, and many headsets
report nothing. **Whatever the OS doesn't expose stays empty — the UI says "Battery not reported"
and never shows invented numbers.** Per-earbud (L/R/case) rings only appear when the device gives them.
The Studio preview and "Test popup" use clearly labelled sample numbers.

## Behaviour notes
- Popups are suppressed during Do Not Disturb when "Respect Do Not Disturb" is on (default).
- "Audio and wearables only" (default on) ignores keyboards, trackers and so on.
- Blur: Android 12+ only. It blurs the screen behind the popup (the OS can't clip it to the popup
  shape), and the popup is not tappable while blur is on.
- Window-based slide animations are limited to a short travel distance so they aren't clipped.
- Some OEMs (Xiaomi, Oppo, Vivo, Samsung "sleeping apps") kill background services: set
  Settings > "Unrestricted battery" for BluePop and allow auto-start in the system settings.
- A battery update shows a popup only if the device had reported nothing before; otherwise the open
  popup updates live.

## Customization
Studio tabs: Style, Look (colour, gradient, transparency, blur, radius, border, shadow, size,
position), Type, Motion (open/close, duration, speed), Layout, Sound & haptics. Every device can
have its own design (Devices > device > "Own popup design"). Themes back up and restore as JSON
(Settings > Themes; device images are not included).

## Update notifications
BluePop checks a small `version.json` that **you host** (any HTTPS website or GitHub Pages). See `update/version.json`.

1. Put the file online, e.g. `https://your-website.com/bluepop/version.json`.
2. Set that address in `UpdateConfig.URL` (`app/src/main/java/app/bluepop/Update.kt`). Until you do, the button says the link isn't set up.
3. Releasing a new version:
   - In `app/build.gradle.kts` raise `versionCode` (e.g. 1 → 2) and `versionName` ("1.1").
   - Build the release APK **signed with your own keystore, the same one every time**. Android refuses to install an update signed with a different key, and the debug key differs per computer.
   - Upload the APK to your website, then edit `version.json`: bump `versionCode`/`versionName`, set `url` to the APK (HTTPS only), add `notes`.
4. Users get a dialog the next time they open the app (checked at most once a day, can be switched off), or any time via Settings > Updates > Check for updates. "Download" opens the APK link in their browser; they install it from there.

The app never installs anything itself. The update check is the only network use and the reason for the INTERNET permission.
