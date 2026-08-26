# Grill — android-wrapper-app

날짜: 2026-08-26 · 결정자: 캡틴(이 항해사 창에서 직접 답변) · 진행: dictation-mate

## Goal

QuicMic 웹 UI를 그대로 띄우는 얇은 안드로이드 래퍼 앱을 만들어, 폰에서 독립 앱으로 실행되게 한다.
배경: 자체 서명 CA 는 Google WebAPK minting 서버가 신뢰하지 못해(구조적 한계, Chromium 40423989)
PWA 독립 설치가 불가능하다고 실물 확정됨(상위 보고서 data/t04-quicmic-review.md). D5 발동.

## Design tree snapshot

```
목표: 폰에서 QuicMic 을 독립 앱으로
├── D1 앱 코드 거주지/저장소 구조 → code/ 모노레포 개편 포함
├── D2 기술 스택·툴체인 → Kotlin+WebView, jinwooauto 동일 고정 버전
├── D3 설치·업데이트 경로 → pnpm install:quicmic (adb USB), 웹 서빙 아님
├── D4 인증서 신뢰 → TOFU (서버 무변경)
├── D5 최소 Android 버전 → API 29+
└── D6 집 밖 동작 → 전용 안내 화면
```

## Round log

- R1: 거주지·스택·배포·신뢰·최소버전·밖동작 6 질문 제시.
  캡틴 중간 답변: 코드 폴더를 `code/android`, `code/web` 식 모노레포로(pnpm workspace 차용) — D1 확장.
- R2: jinwooauto 참조 지시("android 는 jinwooauto 와 동일", "컴파일 때 다른 것 다운받지 않게") — 사실 조사 완료:
  Gradle 8.11.1 / AGP 8.7.3 / Kotlin 2.1.0 / versions.toml 핀 / wrapper git 추적 / install:* = adb USB.
  이 맥에 해당 버전 캐시 존재 → 컴파일 추가 다운로드 최소화 충족.
- R3: 캡틴 — 설치 명칭 `pnpm install:quicmic` 으로만(웹 서빙 폐기), 나머지 추천안 승인.

## Settled decisions

| # | 결정 | 내용 |
|---|---|---|
| D1 | 저장소·구조 | QuicMic 저장소 안 `code/` 모노레포. 기존 `web/` → `code/web` 이동, 새 앱은 `code/android`. Rust(`src/`·`Cargo.toml`)는 루트 유지(Cargo/crates.io 관례·CI 영속) |
| D2 | 스택·툴체인 | 순수 Kotlin + WebView. jinwooauto 와 동일 고정 버전(Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0), libs.versions.toml 핀, gradle wrapper git 추적 |
| D3 | 설치 경로 | `pnpm install:quicmic` = 빌드 + adb USB 설치(jinwooauto scripts/android-install.sh 패턴). APK 웹 서빙 안 함. 맥 .app 설치는 기존 `app:install` 이름 유지 |
| D4 | 인증서 신뢰 | TOFU — 첫 연결 시 서버 인증서 지문 표시→수락 시 저장, 이후 불일치 시 차단+재확인. 서버(Rust) 무변경 |
| D5 | 최소 버전 | Android 10 (API 29)+ |
| D6 | 집 밖 동작 | LAN 도달 불가 시 "집 네트워크가 아닙니다" 전용 안내 화면 |

## Terminology

- **래퍼 앱**: 웹 UI 를 로드하는 얇은 네이티브 껍데기. UI 자체는 서버가 실시간 제공하므로 앱 재배포 없이 UI 갱신됨.
- **TOFU**: Trust On First Use — 첫 접속의 인증서 지문을 사용자 확인 후 고정하는 방식.

## Settled decisions (후속)

| # | 결정 | 내용 |
|---|---|---|
| D7 | 기본 화면 구조 = **V2 두 탭** | 캡틴이 프로토타입(design/default-screen-prototype.html)에서 직접 선택(2026-08-26). 홈 탭=상태·QR 연결 버튼·오디오 미터 / 설정 탭=서버 주소·QR 스캔·수동 PIN 입력·신뢰한 지문 관리. V1 단일 화면·V3 온보딩 게이트는 기각 |
| D8 | QR 스캔 라이브러리 신규 다운로드 **수용** | CameraX 1.3.4+ML Kit barcode 17.3.0(5 아티팩트)은 jinwooauto 캐시에 없어 최초 1회 다운로드 발생. 캡틴이 직접 명한 QR 스캔 기능의 필수 의존이고 버전 고정·온디바이스 추론이라 이후엔 캐시 재활용 — '다운로드 없음' 요건의 취지(매번 무작위 다운로드 방지)와 충돌하지 않는다고 판단(2026-08-26, 항해사 위임 판단) |
