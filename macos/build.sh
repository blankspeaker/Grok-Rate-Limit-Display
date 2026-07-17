#!/usr/bin/env bash
# Build "Grok Rate Limit Display.app" for macOS
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# Finder / Dock / Applications name
PRODUCT_NAME="Grok Rate Limit Display"
# Binary name inside Contents/MacOS (no spaces; matches CFBundleExecutable)
EXEC_NAME="GRLD"
APP_DIR="$ROOT/dist/${PRODUCT_NAME}.app"
CONTENTS="$APP_DIR/Contents"
MACOS="$CONTENTS/MacOS"
RES="$CONTENTS/Resources"

rm -rf "$APP_DIR"
mkdir -p "$MACOS" "$RES"

echo "Compiling ${PRODUCT_NAME}…"
swiftc -O \
  -target arm64-apple-macos13.0 \
  -sdk "$(xcrun --show-sdk-path)" \
  -framework AppKit -framework Foundation -framework Security -framework ServiceManagement \
  -framework UniformTypeIdentifiers \
  -o "$MACOS/$EXEC_NAME" \
  "$ROOT/Sources/"*.swift

cp "$ROOT/Info.plist" "$CONTENTS/Info.plist"
cp "$ROOT/Resources/GaugeLogo.png" "$RES/GaugeLogo.png"
if [[ -f "$ROOT/Resources/DisclaimerIcon.png" ]]; then
  cp "$ROOT/Resources/DisclaimerIcon.png" "$RES/DisclaimerIcon.png"
fi
if [[ -f "$ROOT/Resources/AppIcon.icns" ]]; then
  cp "$ROOT/Resources/AppIcon.icns" "$RES/AppIcon.icns"
fi
if [[ -f "$ROOT/Resources/AppIcon-1024.png" ]]; then
  sips -z 256 256 "$ROOT/Resources/AppIcon-1024.png" --out "$RES/AppIcon.png" >/dev/null 2>&1 || \
    cp "$ROOT/Resources/DisclaimerIcon.png" "$RES/AppIcon.png" 2>/dev/null || true
fi
chmod +x "$MACOS/$EXEC_NAME"

codesign --force --deep --sign - \
  --identifier "com.blankspeaker.GRLD" \
  "$APP_DIR" 2>/dev/null || true

echo "Built: $APP_DIR"

# Optional packaging: ./build.sh --package  → zip + drag-to-Applications DMG
if [[ "${1:-}" == "--package" ]]; then
  "$ROOT/package.sh"
fi
