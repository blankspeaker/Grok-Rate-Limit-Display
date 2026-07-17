#!/usr/bin/env bash
# Package "Grok Rate Limit Display.app" into zip + drag-to-Applications DMG.
# Zip/DMG filenames stay GRLD-macOS-arm64.* for stable download URLs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PRODUCT_NAME="Grok Rate Limit Display"
APP_DIR="$ROOT/dist/${PRODUCT_NAME}.app"
ZIP_OUT="$ROOT/dist/GRLD-macOS-arm64.zip"
DMG_OUT="$ROOT/dist/GRLD-macOS-arm64.dmg"
VOL_NAME="Install Grok Rate Limit Display"
STAGE="$ROOT/dist/dmg-stage"
RW_DMG="$ROOT/dist/.GRLD-rw.dmg"
VOL_PATH="/Volumes/${VOL_NAME}"
BG_SRC="$ROOT/Resources/dmg-background.png"

WIN_W=600
WIN_H=400
ICON_Y=160
ICON_LEFT_X=140
ICON_RIGHT_X=460

detach_vol() {
  if [[ -d "$VOL_PATH" ]]; then
    hdiutil detach "$VOL_PATH" -force >/dev/null 2>&1 || true
  fi
  for v in "${VOL_PATH}" "${VOL_PATH} 1" "${VOL_PATH} 2"; do
    hdiutil detach "$v" -force >/dev/null 2>&1 || true
  done
}

cleanup() {
  detach_vol
  rm -rf "$STAGE" "$RW_DMG"
}

if [[ ! -d "$APP_DIR" ]]; then
  echo "Missing $APP_DIR — run ./build.sh first" >&2
  exit 1
fi
if [[ ! -f "$BG_SRC" ]]; then
  echo "Missing DMG background: $BG_SRC" >&2
  exit 1
fi

echo "Creating zip…"
rm -f "$ZIP_OUT"
ditto -c -k --keepParent "$APP_DIR" "$ZIP_OUT"
echo "  → $ZIP_OUT"

echo "Creating DMG (white + decorative arrows, drag to install)…"
rm -f "$DMG_OUT"
cleanup
mkdir -p "$STAGE/.background"
ditto "$APP_DIR" "$STAGE/${PRODUCT_NAME}.app"
ln -s /Applications "$STAGE/Applications"
cp "$BG_SRC" "$STAGE/.background/background.png"
xattr -cr "$STAGE/${PRODUCT_NAME}.app" 2>/dev/null || true

hdiutil create \
  -volname "$VOL_NAME" \
  -srcfolder "$STAGE" \
  -ov \
  -format UDRW \
  -fs HFS+ \
  -size 50m \
  "$RW_DMG" >/dev/null

detach_vol
hdiutil attach -readwrite -noverify -noautoopen "$RW_DMG" >/dev/null

for _ in $(seq 1 20); do
  [[ -d "$VOL_PATH" ]] && break
  sleep 0.25
done
if [[ ! -d "$VOL_PATH" ]]; then
  echo "Failed to mount $VOL_PATH" >&2
  exit 1
fi

mkdir -p "$VOL_PATH/.background"
cp "$BG_SRC" "$VOL_PATH/.background/background.png"
chflags hidden "$VOL_PATH/.background" 2>/dev/null || true
if command -v SetFile >/dev/null 2>&1; then
  SetFile -a V "$VOL_PATH/.background" 2>/dev/null || true
fi
xattr -cr "$VOL_PATH/${PRODUCT_NAME}.app" 2>/dev/null || true

sleep 0.5

osascript <<EOF
tell application "Finder"
  tell disk "$VOL_NAME"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set the bounds of container window to {180, 120, $((180 + WIN_W)), $((120 + WIN_H))}
    set viewOptions to the icon view options of container window
    set arrangement of viewOptions to not arranged
    set icon size of viewOptions to 128
    set text size of viewOptions to 12
    set background picture of viewOptions to file ".background:background.png"
    set position of item "${PRODUCT_NAME}.app" of container window to {${ICON_LEFT_X}, ${ICON_Y}}
    set position of item "Applications" of container window to {${ICON_RIGHT_X}, ${ICON_Y}}
    update without registering applications
    delay 1
    close
    open
    delay 0.5
    set the bounds of container window to {180, 120, $((180 + WIN_W)), $((120 + WIN_H))}
    close
  end tell
end tell
EOF

sync
sleep 0.5
hdiutil detach "$VOL_PATH" >/dev/null || hdiutil detach "$VOL_PATH" -force >/dev/null

hdiutil convert "$RW_DMG" -format UDZO -imagekey zlib-level=9 -o "$DMG_OUT" >/dev/null
xattr -cr "$DMG_OUT" 2>/dev/null || true
rm -rf "$STAGE" "$RW_DMG"

echo "  → $DMG_OUT"
echo "Packaged:"
ls -lh "$ZIP_OUT" "$DMG_OUT"
