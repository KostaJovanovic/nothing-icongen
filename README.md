# IconGen: Translucent Nothing Icon Generator

IconGen generates and installs a resource-only icon pack on a Nothing device. The pack appears as `IconGen Nothing Translucent Icon Pack`.

The generator application ID and namespace are `space.linuxct.nothing.icongen`. The generated pack keeps `com.nothing.icongen.pack`.

The generator calls the installed Nothing Launcher 5.0.6 implementation, version code `50006009`. It uses the native monochrome artwork, size calculation, foreground colors, and Lucent backdrop tint. It does not replace native extraction with an image approximation. Generation fails if the native renderer rejects an icon or the launcher version does not match.

## Supported devices and limits

The runtime requires a Nothing device with Android 17, API 37, and an `arm64-v8a` processor. Other Android versions and processor architectures are unsupported. The bundled native tools and libraries remain unchanged.

The generator declares `minSdk 22` and `targetSdk 22`. These values describe installation metadata, not support for Android 5.1 or other older systems. The generated pack declares `minSdk 33` and `targetSdk 37`. The generator target does not lower the pack target.

The pack contains separate day and night images. Nothing Launcher can retain old images after a pack update or a theme change. A launcher restart alone does not always refresh them. If icons remain stale, select Nothing icons and then select the generated pack again.

The pack cannot reproduce live widget frosting. Frosting samples and blurs wallpaper at the widget position. An external icon pack supplies static images instead. The generator compensates for the launcher's repeated drawing and shadows to approach the native tint. Light colors retain a small error because PNG colors cannot cancel every black shadow. Wallpaper blur and position-aware sampling remain unavailable.

## Build and install

The build requires JDK 17, a system Gradle installation, and the Android SDK. The project uses Android Gradle Plugin `8.7.3` and Kotlin `2.1.20`. Use Gradle compatible with those plugins. The project does not include a Gradle wrapper.

The generator compiles against Android SDK platform 36. Install that platform and the SDK build tools required by Android Gradle Plugin. SDK Platform Tools provide `adb` for device installation. Gradle also needs access to the dependency repositories during the first build.

The repository includes the signing libraries in `app/libs`, the compiler platform file in `app/src/main/assets/android.jar`, and the native tools in `app/src/main/jniLibs`. Keep these files in a fresh clone. They support pack compilation on the device and do not replace the host Android SDK.

Run all commands below from this repository root, which contains `settings.gradle.kts`. Set `ANDROID_HOME` to your Android SDK directory. Alternatively, create an untracked `local.properties` file with an `sdk.dir` entry for your SDK.

Build the debug APK:

```sh
gradle assembleDebug
```

Enable USB debugging on the supported device. Authorize the computer through the device's USB debugging prompt. Install the debug APK with the low-target bypass:

```sh
adb install -r --bypass-low-target-sdk-block app/build/outputs/apk/debug/app-debug.apk
```

Android 17 blocks ordinary installation of target-22 applications. The bypass flag only changes development installation. The generator cannot bypass this restriction itself.

The optional `:app:buildIconPackApk` and `:app:installIconPackApk` Gradle tasks use a separate host compilation path. These tasks assume a fixed SDK layout, build tools `36.0.0`, platform 35, and a local debug signing key. The host compilation task passes target SDK 35, unlike the primary on-device path's target 37. Use the device workflow below for the documented pack metadata. The fixed SDK layout limits portability of these optional tasks.

## Generate and select the pack

Open `IconGen: Translucent Nothing Icon Generator` on the device. Android controls legacy notification permission review and old-target warnings. The generator does not use a modern runtime notification permission request for its target-22 identity.

Complete setup through the device UI:

1. If Android shows permission review, complete that review.
2. Open `Allow Icon Pack Installation` when the generator shows that button.
3. Allow this generator as a source on the app-specific `Install unknown apps` screen.
4. If notifications are blocked, use the notification access button to open the system notification configuration.

The source permission permits installation of generated APKs. Notification denial does not block generation. It removes progress and installation confirmation notifications. Denied access leaves recovery buttons in the generator instead of repeated prompts.

Use the controls as follows:

- Tap `Generate and Install Icon Pack` to scan applications, generate resources, compile the APK, and submit installation.
- If Android requests installation approval, use the in-app confirmation control or notification to open its approval dialog.
- Tap `Install Icon Pack` to install an available generated APK.
- Tap `Select in Launcher Settings` to open Nothing Launcher's icon pack picker.
- Select `IconGen Nothing Translucent Icon Pack` in the picker.
- Tap `Stop All Generation` to stop active generation and cancel queued generation requests.

Installation submission is not installation success. Android can require approval, especially for the first installation or update. Already-submitted installation has a separate cancellation boundary from generation.

The selection button opens `com.nothing.launcher/com.nothing.launcher.setting.iconpack.IconPackPickerActivity`. You select the pack yourself. The generator does not write `Settings.System` or select the pack automatically.

## Automatic updates

The `Auto-update icon pack on app installs and updates` switch enables automatic rebuild requests. The generator observes package events while its process runs. A persisted periodic job also detects new or changed launchable applications after ordinary process death.

The periodic job has a minimum interval of 15 minutes. Android controls execution and can defer work because of quotas or other scheduling limits. Queued work does not imply that generation started. The generator cannot bypass these limits. If you force-stop the generator, open it again to restore operation.

The first periodic scan records a baseline without generation. Unchanged scans and application removals do not request a rebuild. Startup, permission approval, theme changes, and enabling automatic updates do not request generation by themselves. Events for the generator and its generated pack do not trigger rebuild loops.

Automatic rebuilds use Android's installation process. Android can require user approval even when automatic updates are enabled. The generator presents that approval through its UI or a notification, not a background activity launch.

## Preserve the pack signing key

The signing key identifies the author of an APK update. The generator creates its pack signing key in private storage at `files/iconpack-keystore.jks`. Later pack builds use that same key. This key differs from the debug key that signs the generator itself.

Before clearing generator data or uninstalling it, preserve that key securely. These operations delete private storage and the key. A different generator application ID also receives separate private storage. Renaming the generator does not move the old key or preferences automatically.

If the key is lost, new builds cannot update the installed pack with the old signature. Removing the installed pack permits installation with a new key but removes its installed identity and launcher selection. Keep key backups outside this repository. Do not commit signing material.

For a debuggable installation, `run-as` can copy the key through `adb`. These commands save the current generator's key to a local file:

```sh
adb exec-out run-as space.linuxct.nothing.icongen cat files/iconpack-keystore.jks > iconpack-keystore.jks
```

For a rename migration, export from the old generator before uninstalling it. Replace the application ID in the export command with the old installed ID. Restore only the preserved key into the new debuggable generator before its first generation:

```sh
adb shell run-as space.linuxct.nothing.icongen mkdir -p files
adb shell "run-as space.linuxct.nothing.icongen sh -c 'cat > files/iconpack-keystore.jks'" < iconpack-keystore.jks
```

A target downgrade can fail with `INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE`. A full uninstall and reinstall resets that permission model. Uninstalling with `-k` retains data and does not resolve that failure. Preserve the key before a full uninstall. The pack keeps `com.nothing.icongen.pack` through the generator rename.

## Tests

JVM tests cover composition and durable generation and installation behavior. Instrumentation tests run inside Android and call the installed launcher. They require the supported device and the pinned launcher version. These commands do not promise success on another runtime.

Build the test APKs and run the JVM tests:

```sh
gradle testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Install both APKs with the low-target bypass:

```sh
adb install -r --bypass-low-target-sdk-block app/build/outputs/apk/debug/app-debug.apk
adb install -r --bypass-low-target-sdk-block app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run the launcher and image tests with the new namespace:

```sh
adb shell am instrument -w \
  -e class space.linuxct.nothing.icongen.IconArtworkTest,space.linuxct.nothing.icongen.LauncherParityTest,space.linuxct.nothing.icongen.RealIconPipelineTest,space.linuxct.nothing.icongen.ThemeColorsTest,space.linuxct.nothing.icongen.PaletteDiagnosticsTest,space.linuxct.nothing.icongen.PackAssemblyTest,space.linuxct.nothing.icongen.PackBackdropTest \
  space.linuxct.nothing.icongen.test/androidx.test.runner.AndroidJUnitRunner
```

The tests export images and measurements under the generator's external `files/render-verification` directory. Instrumentation APK updates can trigger automatic generation requests. If you do not want these requests, disable automatic updates before tests.
