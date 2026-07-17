# GRLD — macOS source

**Version 1.0.0** · Menu bar app for weekly SuperGrok usage.

Parent repo: [Grok-Rate-Limit-Display](https://github.com/blankspeaker/Grok-Rate-Limit-Display)

## Requirements

- macOS 13+
- Apple Silicon (arm64)
- Xcode Command Line Tools: `xcode-select --install`

## Build (dev)

```bash
cd macos
./build.sh
open "dist/Grok Rate Limit Display.app"
```

## Package release artifacts

```bash
cd macos
./build.sh --package
# → dist/GRLD-macOS-arm64.zip
# → dist/GRLD-macOS-arm64.dmg
```

Copy into the monorepo `Binaries/` folder and bump `Binaries/mac-latest.json`:

```bash
cp dist/GRLD-macOS-arm64.{zip,dmg} ../Binaries/
# edit ../Binaries/mac-latest.json → "version": "x.y.z"
```

## Version

Set in `Info.plist`:

- `CFBundleShortVersionString` → **1.0.0** (user-facing)
- `CFBundleVersion` → build number

## Auto-update

`Sources/AppUpdater.swift` polls:

`https://raw.githubusercontent.com/blankspeaker/Grok-Rate-Limit-Display/main/Binaries/mac-latest.json`

and downloads the zip named there (default:  
`…/raw/main/Binaries/GRLD-macOS-arm64.zip`).

## Layout

```
macos/
├── Sources/          Swift sources
├── Resources/        Icons (Lucide-style gauge)
├── Info.plist
├── build.sh
├── package.sh
└── dist/             Local build output (not committed)
```

Do **not** commit `dist/`, session cookies, or absolute machine paths in shipping binaries.
