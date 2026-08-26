# Specification — android-wrapper-app

## Goal

QuicMic 웹 UI 를 그대로 띄우는 얇은 안드로이드 래퍼 앱을 만들어 폰에서 독립 앱으로 실행한다.
저장소는 `code/` 모노레포로 개편해 웹 자산과 안드로이드 앱을 한 곳에 모은다.

## User stories

1. 캡틴이 Mac 에서 `pnpm install:quicmic` 을 치면 USB 로 연결된 폰에 앱이 설치되고 실행된다.
2. 앱을 처음 열면 서버 인증서 지문을 보여주고, 수락하면 그 지문을 기억한다.
3. 이후 열 때는 질문 없이 바로 QuicMic 화면(웹 UI)이 전체화면으로 뜬다.
4. 서버 인증서가 바뀌었으면(예: LAN IP 변경 후 재생성) 경고하고 재확인 후 새 지문을 받아들인다.
5. 폰이 집 네트워크에 없으면 "집 네트워크가 아닙니다" 안내 화면이 뜨고 앱은 깔끔하게 유지된다.
6. 앱 안에서의 짝짓기·자동 재연결은 웹과 동일하게 동작한다(토큰은 앱 안에 저장되어 재시작 후에도 유지).
7. 캡틴이 `pnpm build:android` 를 치면 디버그 APK 가 빌드된다.
8. 저장소 구조는 `code/` 모노레포다 — 웹 자산은 `code/web`, 안드로이드 앱은 `code/android` 에 있다.
9. 기존 명령(`pnpm dev:quicmic`, `build:quicmic`, `test:quicmic`, `app:install`)은 모두 여전히 동작한다.

## Scope

- 저장소 구조 개편: 웹 자산 이동 + pnpm workspace 도입
- Kotlin WebView 래퍼 앱(로딩·TOFU 신뢰·안내 화면·앱 아이콘)
- pnpm 스크립트(build/install)와 adb 설치 헬퍼

## Out of scope

- 서버(Rust) 코드 변경 — 인증서 체계·API·전송 방식 무변경
- APK 웹 서빙·업데이트 푸시 — 설치는 adb 로만
- release 서명·Play 스토어 배포
- iOS 대응, 집 밖 스트리밍

## Decisions

D1~D6 은 `grill.md` 단일 출처. 요약: code/ 모노레포(D1) · Kotlin+WebView+jinwooauto 동일 고정 툴체인(D2) ·
pnpm install:quicmic adb 설치(D3) · TOFU(D4) · API 29+(D5) · 집 밖 안내 화면(D6).

## Existing seams / integration points

- 웹 자산은 rust_embed 로 바이너리에 박힌다 — 폴더 경로 상수 하나가 이동의 전부다.
- 웹 UI 는 이미 인증서 지문 확인·토큰 저장·자동 재연결을 스스로 한다 — 래퍼는 화면 컨테이너면 충분하다.
- jinwooauto 의 android 툴체인 관례(고정 버전 versions.toml, git 추적 wrapper, install:* 스크립트)를 그대로 닮는다.

## Data and migration

- 웹 자산 이동은 파일 위치 변경뿐 — 내용·경로 참조(embed 상수 1곳) 수정.
- 앱은 지문 문자열을 앱 내부 저장소에 하나 보관. 마이그레이션 없음.

## Security / authorization

- TOFU 지문 고정이 유일한 신뢰 앵커다. 지문 불일치 시 연결을 거부하고 사용자 재확인을 요구한다.
- 앱은 LAN 외 아무 곳도 접속하지 않는다. 인터넷 권한만 선언한다.

## Compatibility / rollout

- 기존 명령 전부 유지. `install:quicmic` 이름은 안드로이드 설치가 가져가고, 맥 .app 은 기존 `app:install` 로 계속 설치된다.
- 웹 자산 이동 후 첫 빌드·테스트가 회귀선이다.

## Acceptance criteria

1. `pnpm build:android` 로 디버그 APK 가 빌드된다(추가 네트워크 다운로드 최소 — jinwooauto 와 동일 버전 캐시 재활용).
2. `pnpm install:quicmic` 으로 USB 폰에 설치·실행된다.
3. 첫 실행에서 지문 수락 → 두 번째 실행부터 질문 없이 전체화면 웹 UI.
4. 인증서 변경 감지 시 차단 후 재확인으로 복구된다.
5. LAN 밖에서는 안내 화면이 뜬다.
6. 앱 안 짝짓기→재시작→무번호 재연결이 웹과 동일하게 된다.
7. 웹 자산 이동 후 `cargo test` 전체 통과, 기존 pnpm 명령 전부 정상.

## Verification strategy

T01 은 cargo test + 전 pnpm 명령 실측. T02 는 에뮬레이터/USB 폰 실측.
말단 검수 티켓 필수 — 캡틴이 실물 폰에서 설치→신뢰→짝짓기→재연결을 직접 확인한다.

## User stories (후속 — D7, 2026-08-26)

10. 앱을 열면 홈 탭과 설정 탭이 하단에 있고, 홈에는 연결 상태와 "QR로 연결" 버튼이 보인다.
11. 설정 탭에서 서버 주소 확인·수동 PIN 입력·신뢰한 인증서 지문 관리를 할 수 있다.
12. "QR로 연결"을 누르면 카메라 스캐너가 열리고 Mac 메뉴바 QR을 찍으면 주소·PIN이 자동 채워져 연결된다.
13. 연결 중에는 홈 탭에 오디오 입력 레벨 미터가 보인다.
