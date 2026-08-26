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

# Optional output device override: --device <name> flag or QUICMIC_DEVICE env var.
# The binary itself reads QUICMIC_DEVICE (clap env), so the value is baked into
# the bundle's LSEnvironment instead of a launcher script: LaunchServices only
# associates a process with its bundle when CFBundleExecutable IS the real
# binary, and a shell wrapper broke that association - the server ran but no
# menu-bar item could ever appear.
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

mkdir -p "$APP/Contents/MacOS"
rm -f "$APP/Contents/MacOS/quicmic.bin" "$APP/Contents/device.conf"

LSENV=""
if [ -n "$DEVICE" ]; then
  LSENV="  <key>LSEnvironment</key>
  <dict>
    <key>QUICMIC_DEVICE</key> <string>$DEVICE</string>
  </dict>
"
fi

cat > "$APP/Contents/Info.plist" <<PLIST
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
  <key>NSMicrophoneUsageDescription</key> <string>QuicMic streams your phone's microphone into a virtual audio device.</string>
  <key>LSUIElement</key>             <true/>
$LSENV</dict>
</plist>
PLIST

# The real binary IS the bundle executable - no wrapper (see the note above).
cp -f "$BIN" "$APP/Contents/MacOS/quicmic"
chmod +x "$APP/Contents/MacOS/quicmic"

# Ad-hoc sign so Gatekeeper/local execution is clean (--deep seals device.conf)
codesign --force --deep --sign - "$APP"
# Refresh LaunchServices so the rebuilt bundle (and its LSEnvironment) is picked up.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP" >/dev/null 2>&1 || true

# LaunchServices needs a moment to accept a freshly written bundle: launching
# during that window fails with -609, which reads like a broken install. A
# name-resolution probe is NOT enough - it succeeds while the launch still
# fails - so the only trustworthy readiness signal is a launch that actually
# produces a running process.
if pgrep -f "$APP/Contents/MacOS/quicmic" >/dev/null 2>&1; then
  echo "✔ Installed: $APP"
  echo "ℹ️  An older instance is still running - quit it from the menu bar and"
  echo "   launch again to pick up this build."
  exit 0
fi

for _ in $(seq 1 30); do
  if open -a "$APP" >/dev/null 2>&1 && sleep 2 &&
     pgrep -f "$APP/Contents/MacOS/quicmic" >/dev/null 2>&1; then
    echo "✔ Installed and launched: $APP (menu-bar icon, no Dock item)"
    exit 0
  fi
  sleep 1
done

echo "✔ Installed: $APP" >&2
echo "⚠️  macOS did not accept the freshly registered app in time." >&2
echo "   Wait a few seconds and run: open -a QuicMic" >&2
