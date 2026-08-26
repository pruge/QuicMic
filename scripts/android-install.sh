#!/usr/bin/env bash
# QuicMic Android wrapper 앱 설치 헬퍼 (adb USB 전용).
#
# 사용법:
#   scripts/android-install.sh              # 디바이스 1대면 자동, 여러 대면 ↑/↓ 커서 메뉴
#   ANDROID_SERIAL=<serial> scripts/android-install.sh
#
# 동작: gradlew 검증 → assembleDebug → adb devices 열거 → install -r -d → 실행.
# jinwooauto scripts/android-install.sh 참조(커서 메뉴 포함 현재판). QuicMic 은 앱 1개·
# debug 트랙 전용이라 --prod 와 트랙 sentinel 은 대상이 아니다.

set -euo pipefail

APP_DIR="code/android"
APP_ID="com.pruge.quicmic"
APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

if [ ! -x "$APP_DIR/gradlew" ]; then
  echo "⚠️  $APP_DIR/gradlew 없음 -- wrapper 는 git-tracked. git restore code/android/gradlew code/android/gradle/ 로 복구하거나 Android Studio 로 open + sync 하세요." >&2
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

# 대상 결정: env > 단일 디바이스 자동 > 다중이면 커서 메뉴(jinwooauto 현재판)
if [ -n "${ANDROID_SERIAL:-}" ]; then
  TARGETS=("$ANDROID_SERIAL")
elif [ ${#DEVICES[@]} -eq 1 ]; then
  TARGETS=("${DEVICES[0]}")
else
  LABELS=()
  for i in "${!DEVICES[@]}"; do
    serial="${DEVICES[$i]}"
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
    LABELS+=("$((i + 1))) $serial -- ${model:-?}")
  done
  LABELS+=("all) 전체 (${#DEVICES[@]}대)")
  ALL_INDEX=${#DEVICES[@]}   # LABELS 의 마지막 = all

  echo ""
  echo "디바이스 ${#DEVICES[@]}대 감지:"

  if [ -t 0 ] && [ -t 1 ]; then
    # ── 커서 메뉴: ↑/↓(또는 j/k) 로 순환 이동, Enter 선택 ──
    echo "  ↑/↓ 이동 (순환), Enter 선택"
    n=${#LABELS[@]}
    sel=0
    printf '\033[?25l'                              # 커서 숨김
    trap 'printf "\033[?25h"' EXIT                  # 종료 시 커서 복원
    first=1
    while true; do
      if [ "$first" -eq 1 ]; then first=0; else printf '\033[%dA' "$n"; fi
      for i in "${!LABELS[@]}"; do
        if [ "$i" -eq "$sel" ]; then
          printf '\033[2K\r  \033[7m ▸ %s \033[0m\n' "${LABELS[$i]}"
        else
          printf '\033[2K\r     %s\n' "${LABELS[$i]}"
        fi
      done
      key=""
      IFS= read -rsn1 key || true
      if [ "$key" = $'\033' ]; then
        rest=""
        IFS= read -rsn2 rest || true   # 화살표는 ESC 뒤 '[A'/'[B' 2바이트 즉시 도착
        key+="$rest"
      fi
      case "$key" in
        $'\033[A' | 'k') sel=$(((sel - 1 + n) % n)) ;;   # 위 (wrap)
        $'\033[B' | 'j') sel=$(((sel + 1) % n)) ;;        # 아래 (wrap)
        '' | $'\n' | $'\r') break ;;                      # Enter
      esac
    done
    printf '\033[?25h'                              # 커서 복원
    trap - EXIT
    CHOICE_INDEX=$sel
  else
    # 비대화형(TTY 아님): 텍스트 입력 fallback
    for label in "${LABELS[@]}"; do echo "  $label"; done
    echo -n "선택 [1-${#DEVICES[@]} / all] (기본 all, Enter): "
    read -r CHOICE
    if [ -z "$CHOICE" ] || [ "$CHOICE" = "all" ]; then
      CHOICE_INDEX=$ALL_INDEX
    elif [[ "$CHOICE" =~ ^[0-9]+$ ]] && [ "$CHOICE" -ge 1 ] && [ "$CHOICE" -le ${#DEVICES[@]} ]; then
      CHOICE_INDEX=$((CHOICE - 1))
    else
      echo "잘못된 선택: $CHOICE_ARG (1-${#DEVICES[@]} | all)" >&2
      exit 1
    fi
  fi

  if [ "$CHOICE_INDEX" -eq "$ALL_INDEX" ]; then
    TARGETS=("${DEVICES[@]}")
  else
    TARGETS=("${DEVICES[$CHOICE_INDEX]}")
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
