#!/usr/bin/env bash
# QuicMic Android wrapper 앱 설치 헬퍼 (adb USB 전용).
#
# 사용법:
#   scripts/android-install.sh              # 디바이스 1대면 자동, 여러 대면 번호 선택
#   ANDROID_SERIAL=<serial> scripts/android-install.sh
#
# 동작: gradlew 검증 → assembleDebug → adb devices 열거 → install -r -d → 실행.
# jinwooauto scripts/android-install.sh 의 단순화판(앱 1개, debug 만, --prod 불필요).

set -euo pipefail

APP_DIR="code/android"
APP_ID="com.pruge.quicmic"
APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

if [ ! -x "$APP_DIR/gradlew" ]; then
  echo "⚠️  $APP_DIR/gradlew 없음 -- Android Studio 로 $APP_DIR 을 open + Gradle sync 한 번 실행하세요 (wrapper 자동 생성)." >&2
  exit 1
fi

command -v adb >/dev/null 2>&1 || { echo "❌ adb 없음 -- Android SDK platform-tools 를 PATH 에 넣어주세요." >&2; exit 1; }

echo "▶ 빌드: assembleDebug"
( cd "$APP_DIR" && ./gradlew assembleDebug )
if [ ! -f "$APK" ]; then
  echo "❌ APK 빌드 실패: $APK 없음" >&2
  exit 1
fi

# 디바이스 열거
DEVICES=()
while IFS= read -r line; do
  serial=$(awk '{print $1}' <<<"$line")
  state=$(awk '{print $2}' <<<"$line")
  if [ "$state" = "device" ] && [ -n "$serial" ]; then
    DEVICES+=("$serial")
  fi
done < <(adb devices | tail -n +2 | sed '/^$/d')

if [ ${#DEVICES[@]} -eq 0 ]; then
  echo "❌ 연결된 디바이스 없음. USB 로 폰을 연결하고 \`adb devices\` 를 확인하세요." >&2
  exit 1
fi

# 대상 결정: env > 단일 디바이스 자동 > 다중이면 번호 선택
if [ -n "${ANDROID_SERIAL:-}" ]; then
  TARGETS=("$ANDROID_SERIAL")
elif [ ${#DEVICES[@]} -eq 1 ]; then
  TARGETS=("${DEVICES[0]}")
else
  echo ""
  echo "디바이스 ${#DEVICES[@]}대 감지:"
  for i in "${!DEVICES[@]}"; do
    serial="${DEVICES[$i]}"
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
    echo "  $((i + 1))) $serial -- ${model:-?}"
  done
  read -r -p "선택 [1-${#DEVICES[@]}/all]: " CHOICE
  if [ "$CHOICE" = "all" ]; then
    TARGETS=("${DEVICES[@]}")
  elif [[ "$CHOICE" =~ ^[0-9]+$ ]] && [ "$CHOICE" -ge 1 ] && [ "$CHOICE" -le ${#DEVICES[@]} ]; then
    TARGETS=("${DEVICES[$((CHOICE - 1))]}")
  else
    echo "잘못된 선택: $CHOICE" >&2
    exit 1
  fi
fi

for SERIAL in "${TARGETS[@]}"; do
  echo ""
  echo "▸ install: $SERIAL"
  # install -r -d : 업그레이드 + 다운그레이드 허용. debug 트랙만 다루므로
  # 서명 불일치는 uninstall 후 재설치로 회복하면 충분하다.
  if ! INSTALL_OUT=$(adb -s "$SERIAL" install -r -d "$APK" 2>&1); then
    echo "$INSTALL_OUT" >&2
    if grep -qiE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_VERSION_DOWNGRADE' <<<"$INSTALL_OUT"; then
      echo "  ↻ 서명/버전 불일치 — 기존 설치 제거 후 재설치"
      adb -s "$SERIAL" uninstall "$APP_ID" || true
      adb -s "$SERIAL" install "$APK"
    else
      echo "❌ install 실패: $SERIAL" >&2
      exit 1
    fi
  fi
  adb -s "$SERIAL" shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
done

echo ""
echo "✅ QuicMic 앱 설치 완료 (${#TARGETS[@]}대)"
