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

# Optional output device override: --device <name> flag or QUICMIC_DEVICE env var.
# Recorded into Contents/device.conf; the wrapper exec adds --device at launch.
DEVICE="${QUICMIC_DEVICE:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --device)
      [ $# -ge 2 ] || { echo "error: --device requires a value" >&2; exit 1; }
      DEVICE="$2"
      shift 2
      ;;
    *) echo "error: unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Real binary lives under a different name; Contents/MacOS/quicmic is a wrapper.
cp -f "$BIN" "$APP/Contents/MacOS/quicmic.bin"
rm -f "$APP/Contents/device.conf"
if [ -n "$DEVICE" ]; then
  printf '%s\n' "$DEVICE" > "$APP/Contents/device.conf"
fi

cat > "$APP/Contents/MacOS/quicmic" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ARGS=()
# QUICMIC_DEVICE env var wins over the bundled device.conf.
if [ -n "${QUICMIC_DEVICE:-}" ]; then
  ARGS+=(--device "$QUICMIC_DEVICE")
elif [ -f "$DIR/../device.conf" ]; then
  DEV="$(head -n 1 "$DIR/../device.conf")"
  if [ -n "$DEV" ]; then
    ARGS+=(--device "$DEV")
  fi
fi
exec "$DIR/quicmic.bin" "${ARGS[@]}"
WRAPPER
chmod +x "$APP/Contents/MacOS/quicmic"

# Ad-hoc sign so Gatekeeper/local execution is clean (--deep seals device.conf)
codesign --force --deep --sign - "$APP"

echo "✔ Installed: $APP (launch with 'open -a QuicMic')"
