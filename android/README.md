# GRLD — Android source

**Version 1.0.0** · Ongoing notification / Live Update status chip + full usage UI.

Parent repo: [Grok-Rate-Limit-Display](https://github.com/blankspeaker/Grok-Rate-Limit-Display)

## Requirements

- JDK **17**
- Android SDK (API 34 compile / min 26)
- Device or emulator with USB debugging (for install)

## Build (debug)

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or your JDK 17 path
export ANDROID_HOME=/path/to/Android/sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored

./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release signing (publish APKs)

**This repo does not include a keystore.** Debug APKs are fine for local testing;
**GitHub release APKs should be release-signed with your own key.**

1. Create a keystore once (keep it private forever):

```bash
keytool -genkeypair -v -keystore ~/my-grld-release.jks -alias grld \
  -keyalg RSA -keysize 2048 -validity 10000
```

2. Point the build at it (pick one; never commit these files):

**`android/keystore.properties`** (gitignored):

```properties
storeFile=/absolute/path/to/my-grld-release.jks
storePassword=...
keyAlias=grld
keyPassword=...
```

Or env vars: `GRLD_STORE_FILE`, `GRLD_STORE_PASSWORD`, `GRLD_KEY_ALIAS`, `GRLD_KEY_PASSWORD`.

3. Build and publish:

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../Binaries/GRLD-android.apk
# edit ../Binaries/android-latest.json → version + versionCode
```

Use the **same** keystore for every update of a given install, or Android will
refuse to upgrade and Play Protect warnings are more likely with debug certs.

## Version

In `app/build.gradle.kts`:

```kotlin
versionCode = 1
versionName = "1.0.0"
```

## Features (high level)

- Status-bar gauge icon + notification tray (segmented usage bar)
- Dynamic home-screen app icon (10% remaining steps)
- Multi-color category bar + colored dots / labels
- Week chart (1–3 weeks on foldables / tablets)
- Settings gear → full settings page
- SuperGrok / SuperGrok Heavy required for usage API

## Layout

```
android/
├── app/src/main/java/com/blankspeaker/grld/
├── app/src/main/res/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

Do **not** commit `local.properties`, `app/build/`, cookies, or session dumps.
