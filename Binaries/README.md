# Prebuilt binaries

| File | Platform | Notes |
|------|----------|--------|
| `GRLD-macOS-arm64.dmg` | macOS 13+ Apple Silicon | Drag to Applications |
| `GRLD-macOS-arm64.zip` | macOS | Used by Mac in-app Auto-Update |
| `GRLD-android.apk` | Android 8+ | Install with `adb install -r` |
| `mac-latest.json` | — | Version manifest for Mac updater |
| `android-latest.json` | — | Version / versionCode for Android builds |

## Publishing

1. Bump versions in `macos/Info.plist` and `android/app/build.gradle.kts`
2. Build release artifacts (see platform READMEs)
3. Replace files here; update JSON `version` fields
4. Commit and push `main`

**Never commit keystores or `signing.properties`.**

Direct links on `main`:

- Mac zip: `https://github.com/blankspeaker/Grok-Rate-Limit-Display/raw/main/Binaries/GRLD-macOS-arm64.zip`
- Android APK: `https://github.com/blankspeaker/Grok-Rate-Limit-Display/raw/main/Binaries/GRLD-android.apk`
