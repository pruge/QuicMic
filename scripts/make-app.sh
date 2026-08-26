#!/usr/bin/env bash
# Build /Applications/QuicMic.app from the release binary (macOS only).
# Menu-bar-only app: LSUIElement=true, so no Dock icon.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${QUICMIC_APP_DIR:-/Applications/QuicMic.app}"
BIN="$REPO/target/release/quicmic"

if [ ! "$(uname)" = "Darwin" ]; then
  echo "error: make-app.sh only works on macOS" >&2
  exit 1
fi
if [ ! -x "$BIN" ]; then
  echo "error: release binary not found at $BIN — run 'pnpm build' first" >&2
  exit 1
fi

mkdir -p "$APP/Contents/MacOS"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>            <string>QuicMic</string>
  <key>CFBundleDisplayName</key>     <string>QuicMic</string>
  <key>CFBundleIdentifier</key>      <string>com.pruge.QuicMic</string>
  <key>CFBundleVersion</key>         <string>1.0</string>
  <key>CFBundleShortVersionString</key> <string>1.0</string>
  <key>CFBundleExecutable</key>      <string>quicmic</string>
  <key>CFBundlePackageType</key>     <string>APPL</string>
  <key>LSUIElement</key>             <true/>
</dict>
</plist>
PLIST

cp -f "$BIN" "$APP/Contents/MacOS/quicmic"

# Ad-hoc sign so Gatekeeper/local execution is clean
codesign --force --sign - "$APP"

echo "✔ Installed: $APP (launch with 'open -a QuicMic')"
