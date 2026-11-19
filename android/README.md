# Sharpening Guide: Android companion app

A big-screen companion display for the stick. The phone, tablet or TV fills with the
stick's own colour (green = on angle, blue = too low, red = too high) and shows the live
angle in huge digits. You can also change the stick's settings and update its firmware
over Bluetooth.

It uses the BLE protocol in [`../docs/companion-protocol.md`](../docs/companion-protocol.md).
You need stick firmware with the companion service. Android 8.0 (API 26) or newer with
Bluetooth LE.

## Build

You need a JDK 17 or newer and the Android SDK (platform 35, build-tools 35.0.0). Either
open this folder in Android Studio, or from the command line:

```sh
cd android
# local.properties must point at your SDK (Android Studio writes it for you):
echo 'sdk.dir=C\:/Users/<you>/Android/Sdk' > local.properties   # Windows
# echo "sdk.dir=$HOME/Android/Sdk" > local.properties           # Linux/macOS

./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # protocol / OTA image / asset picker tests
```

## Install

With USB debugging on:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it. You need to allow "Install unknown
apps" for the file manager you open it with.

## Use

1. **Connect.** On first launch, allow the *Nearby devices* permission. On Android 11 and
   older this is the *Location* permission, which Android needs for Bluetooth scanning. Turn
   the stick on, then tap it in the list (`SharpGuide-XXXX`). The app remembers it. Each time
   you launch, it reconnects by itself. If the link drops, it keeps retrying quietly. Tap the
   device name at the top of the Live screen to pick another stick or forget this one.
2. **Live.** The background is the stick's colour, and the number is the live angle. It is
   drawn straight from each notification, with no smoothing or animation. Under the number
   you see the target, tolerance, side and strokes, on-angle %, battery, and the stick's
   screen when it isn't coaching (for example *Zero calibration* or *Summary*).
   - **Decimals** cycles through *Auto* (whatever the stick's tenths setting says), *0*
     and *0.0*.
   - **Fullscreen** hides the system bars and the tab bar. Tap anywhere to leave it.
   - The screen stays on while a stick is connected.
3. **Settings.** Set the target angle (5.0–45.0° in 0.5° steps, or a preset), tolerance,
   buzzer, steady mode and tenths. Then tap **Write to stick**. The values it actually
   applied appear below the button.
4. **Firmware.** This screen shows the installed version and the board.
   - **Check GitHub** looks up the latest release of the repo in the box. The default is
     `redrob2/digital-sharpening-guide`. It picks the `.bin` for your board. Then tap
     **Download**.
   - **Pick .bin file…** opens a file from the phone instead.

   Either kind of file works: the merged web-flasher image
   (`sharpening-guide-<board>-v<ver>.bin`) or a plain `firmware.bin`. The app checks that
   the file was built for your stick's board and refuses it if not. Then tap **Upload to
   stick**. An update takes about a minute. When it's done the stick reboots, and the app
   reconnects to it. **Cancel** aborts the update, and the stick keeps its current firmware.

## Signed release build

`./gradlew :app:assembleRelease` signs with the keystore named in
`android/keystore.properties` (git-ignored, never commit it):

```
storeFile=C:/path/to/sharpguide-release.jks
storePassword=...
keyAlias=sharpguide
keyPassword=...
```

Without that file the release APK comes out unsigned. Keep the keystore and its
passwords backed up: an app installed from a release APK can only be updated by
another APK signed with the same key.
