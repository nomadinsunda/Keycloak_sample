# 검증 기록

이 프로젝트의 핵심 결과물입니다. 설계대로 동작한 것, 설계와 다르게 처리해야 했던 것, 구현하면서 알게 된 함정을 기록합니다.

## 2026-11-11 — 0~8단계 구현·검증

### 사용 버전

| 구성 요소 | 버전 |
|---|---|
| Spring Boot | 4.1.2 |
| Spring Cloud | 2025.1.4 (Gateway: `spring-cloud-starter-gateway-server-webflux`, Netty) |
| Java | 21 (eclipse-temurin 21 alpine 이미지) |
| Keycloak | 26.8.1 |
| PostgreSQL | 18.6-alpine |
| React / Vite | 19.3.1 / 8.3.8 |
| Tailwind CSS / daisyUI | 4.3.5 / 5.7.50 |
| Nginx / Node (빌드) | 1.30.6-alpine / 24-alpine |

### 결과 요약

- `scripts/e2e-test.sh`: **32개 항목 모두 통과**
- Chrome 수동 확인: 로그인 → 직원 목록 조회 → 직원 등록(CSRF) → 로그아웃 모두 정상. 백오피스(`localhost:3000`)와 Gateway(`localhost:8080`)의 Origin이 다른 상태에서 쿠키·CORS·CSRF가 실제 브라우저에서 동작함을 확인

| 검증 항목 | 결과 |
|---|---|
| Keycloak을 Gateway 뒷단(`/iam/**`)에 두고 외부 포트 미공개 | 동작 |
| Gateway 경유와 내부 직접 호출에서 Keycloak `iss`가 같음 | 동작 (`KC_HOSTNAME` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`) |
| Authorization Code + PKCE(S256), `state`·`nonce` 검증 | 동작 |
| `state` 재사용·위조 거부 (로그인 트랜잭션 사용 즉시 삭제) | 동작 |
| Employee Auth Service의 Keycloak 호출이 Gateway를 경유하지 않음 | 동작 |
| PMart JWT(RS256) 발급, `Set-Cookie`(`HttpOnly`, `SameSite=Lax`) 전달 | 동작 |
| Gateway가 쿠키 JWT를 검증하고 하위 서비스에 `Authorization: Bearer`로 전달 | 동작 |
| Flutter 방식(`Authorization` 헤더)도 같은 Gateway에서 동작 | 동작 |
| JWKS 내부 직접 호출, 외부 비노출 | 동작 |
| CORS: 백오피스 Origin만 허용, 헤더 중복 없음 | 동작 |
| CSRF: 토큰 없음·잘못된 토큰·다른 Origin 거부 | 동작 |
| 직원 등록 → Keycloak 계정 발급, 같은 사번 재등록 409 | 동작 |
| 새 직원 임시 비밀번호 → 첫 로그인 시 비밀번호 변경 | 동작 |
| 역할 기반 인가 (`HR_ADMIN` 아닌 직원은 403) | 동작 |
| 테스트용 인사팀 계정 부팅 시 생성, 재시작해도 중복 없음 | 동작 |
| 서명키 재시작 후 유지, 기존 토큰 계속 유효 | 동작 |

### 설계에 없던 것을 구현하며 정한 것

| 항목 | 정한 내용 | 이유 |
|---|---|---|
| 부트스트랩용 Keycloak Client | `pmart-employee-auth-bootstrap` (Client Credentials, `manage-users`·`view-users`·`query-users`) | Employee Auth Service가 테스트 계정을 만들려면 Admin API 권한이 필요. 로그인용 Client `pmart-employee-auth`의 Service Accounts는 OFF 유지 |
| 직원 정보 조회 | Employee Service 내부 API `/internal/employees/**` + 내부 API 키(`X-Internal-Api-Key`) | Employee Auth Service가 로그인 시 재직 상태·역할을 확인해야 함. Gateway 라우트가 없어 외부 접근 불가. **테스트 프로젝트용 단순 방식** |
| 역할 저장 위치 | Employee Service DB (`EMPLOYEE`, `HR_ADMIN`) | Keycloak에는 역할을 두지 않음. PMart 권한은 PMart가 관리 |
| PMart JWT `aud` | `pmart-employee-auth`를 추가 | `/employee-auth/me`도 PMart JWT로 보호하기 때문 |
| 서비스별 경로 | `/employee-auth/**`, `/employee-service/**`, `/sample-api/**` (Gateway에서 경로 변경 없이 전달) | 라우트 설정 단순화 |
| 로그인 시작 | `GET /employee-auth/oauth2/start`가 Keycloak으로 302 | SPA는 페이지 이동으로 로그인 시작. Flutter용 JSON 응답은 10단계에서 추가 |
| Keycloak 사용자 이메일 | `<사번 소문자>@pmart.test` 자동 생성 | Keycloak 기본 User Profile이 이메일을 요구해 첫 로그인 때 프로필 입력 화면이 뜨는 것을 방지 |

### 구현하면서 발견한 함정

1. **Spring Boot 4 starter 이름 변경**: `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server`, Flyway도 `spring-boot-starter-flyway`가 필요. Boot 3 예제를 그대로 쓰면 안 됨.
2. **Spring Cloud Gateway 설정 접두사 변경**: 라우트는 `spring.cloud.gateway.server.webflux.routes`. 예전 `spring.cloud.gateway.routes`는 쓰지 않음.
3. **오류 응답이 403으로 바뀌는 문제**: `anyRequest().denyAll()`이 Spring Boot 오류 경로 `/error`까지 막아, 400·500 같은 모든 오류가 403으로 응답됨. 각 서비스에서 `/error`를 `permitAll`로 열어 해결.
4. **만료된 쿠키로 로그인이 막힐 수 있는 문제**: Spring Security Resource Server는 토큰이 있으면 공개 경로에서도 검증 실패 시 401을 반환함. Gateway의 쿠키 토큰 변환기가 공개 경로(`/employee-auth/oauth2/start` 등)에서는 쿠키를 읽지 않도록 처리.
5. **Keycloak은 username을 소문자로 저장**: `HR000001`은 Keycloak에 `hr000001`로 저장됨. 직원 매핑은 Keycloak UUID(`sub`)로 하므로 문제없지만, username으로 조회·비교할 때 주의.
6. **임시 비밀번호 로그인 흐름**: Keycloak은 비밀번호 변경 화면을 바로 보여주지 않고 `required-action` 주소로 302 이동시킴. 자동화 테스트에서 이 이동을 따라가야 함.
7. **Keycloak 로그아웃 확인 화면**: `id_token_hint` 없이 로그아웃하면 Keycloak이 "로그아웃하시겠습니까?" 확인 화면을 띄움. Keycloak ID Token을 저장하지 않는 설계이므로 확인 화면이 한 번 나옴. 없애려면 ID Token 보관이 필요해 현재는 그대로 둠.
8. **Windows Git Bash의 curl 한글 인코딩**: curl 인자로 한글 JSON을 넘기면 UTF-8이 아닌 인코딩으로 전송됨. 테스트 스크립트는 UTF-8 파일로 본문을 보냄.
9. **Well-known의 `token_endpoint`**: Gateway를 거쳐 조회해도 `token_endpoint`가 내부 주소(`http://keycloak:8080/...`)로 나옴. Gateway가 `Host` 헤더를 내부 주소로 바꿔 전달하기 때문. 브라우저는 `authorization_endpoint`만 쓰므로 영향 없음.

## 2026-11-11 — 9단계 Refresh Token·퇴사 처리

### 결과 요약

- `scripts/e2e-test.sh`: **48개 항목 모두 통과** (기존 32개 + 신규 16개)
- 브라우저 수동 확인은 하지 않음. 백오피스의 자동 갱신(401 → refresh → 재시도)은 Access Token 만료(15분)를 기다려야 해서, 쿠키 삭제로 만료 상황을 만드는 스크립트 검증으로 대신함

| 검증 항목 | 결과 |
|---|---|
| Refresh Token 쿠키 `HttpOnly`, `Path=/employee-auth` | 동작 |
| CSRF 토큰 없는 갱신 거부 | 동작 (403) |
| Rotation: 갱신마다 Access·Refresh Token 모두 새로 발급 | 동작 |
| Access Token 만료 후 Refresh Token으로 갱신 → API 호출 | 동작 |
| 사용한 Refresh Token 재사용 → 거부 + 같은 세션의 정상 토큰까지 폐기 | 동작 |
| 퇴사 처리 → 토큰 갱신 거부, 쿠키 삭제 | 동작 |
| 퇴사 처리 → Keycloak 재로그인 거부 ("계정 비활성화") | 동작 |
| 자기 자신 비활성화 거부 | 동작 (400) |

### 설계에 없던 것을 구현하며 정한 것

| 항목 | 정한 내용 | 이유 |
|---|---|---|
| Employee Auth 내부 API | `/internal/sessions/revoke` + 내부 API 키 | Employee Service가 퇴사 처리 시 Refresh Token 폐기를 요청하기 위해. Gateway 라우트 없음 |
| 퇴사 처리 API | `POST /employee-service/employees/{사번}/deactivate` (HR_ADMIN) | 백오피스 직원 목록에 "비활성화" 버튼 |
| 퇴사 처리 실패 시 | 직원 DB는 먼저 `INACTIVE`로 바꾸고, Keycloak·세션 정리 실패 시 502 + 재시도 안내 | DB가 `INACTIVE`이면 토큰 갱신과 로그인 확인 단계에서 이미 거부되므로 안전한 쪽으로 먼저 처리 |

### 알게 된 점

1. **동시 갱신과 재사용 탐지의 충돌**: 같은 Refresh Token으로 두 요청이 동시에 갱신하면 두 번째 요청은 재사용으로 판단되어 세션 전체가 폐기됨. SPA에서 갱신 요청을 하나로 묶어 해결했지만, 브라우저 탭 여러 개가 동시에 갱신하는 경우는 여전히 로그아웃될 수 있음. 운영 수준이라면 짧은 유예 시간(grace period)이 필요함.
2. **퇴사 후 Access Token 유효 시간**: Refresh Token과 Keycloak 계정은 즉시 막히지만, 이미 발급된 Access Token은 최대 15분 동안 유효함. 자체 JWT를 쓰는 구조의 본질적인 제약이며, 즉시 차단이 필요하면 `jti` 차단 목록 같은 별도 장치가 필요함.
3. **Keycloak 세션 정리는 명시적으로 호출**: 계정 비활성화(`enabled=false`)와 함께 `/users/{id}/logout`도 호출해 기존 Keycloak SSO 세션을 정리함. 비활성화만으로 기존 세션이 끊기는지는 따로 검증하지 않음.

## 2026-11-11 — 10단계 Flutter 앱

### 사용 버전

| 구성 요소 | 버전 |
|---|---|
| Flutter / Dart | 3.47.2 / 3.13.2 |
| flutter_web_auth_2 | 5.1.0 (Custom Tabs / ASWebAuthenticationSession) |
| flutter_secure_storage | 11.2.0 |
| http / crypto | 1.6.0 / 3.0.8 |
| 확인 기기 | Android 16 에뮬레이터 (`dayfold_test`), Chrome Custom Tabs |

### 결과 요약

- `scripts/e2e-test.sh`: **65개 항목 모두 통과** (앱 서버 흐름 17개 추가, 앱 동작을 curl로 재현)
- 에뮬레이터 실제 실행 확인: 로그인 → Custom Tab에서 Keycloak 로그인 → 앱 복귀 → HR_ADMIN API 200 → 토큰 갱신 → 앱 재시작 후 로그인 유지 → 로그아웃. 로그아웃 후 DB에서 해당 세션의 Refresh Token이 모두 폐기된 것 확인
- `flutter analyze` 경고 0, debug APK 빌드 성공
- **iOS는 확인하지 않음** (Windows 환경이라 빌드 불가). iOS 프로젝트 폴더만 생성됨

| 검증 항목 | 결과 |
|---|---|
| `app_challenge` 없는 앱 로그인 시작 거부 | 동작 (400) |
| 앱 복귀 URL에는 ticket만 있고 토큰·쿠키 없음 | 동작 |
| 다른 `app_verifier`로 ticket 교환 (가로챈 ticket) | 거부 (401) |
| 교환에 실패한 ticket은 정상 verifier로도 재사용 불가 | 동작 |
| 정상 ticket 교환, 같은 ticket 재사용 거부 | 동작 |
| 앱 Access Token으로 Bearer API 호출 | 동작 |
| 앱 Refresh Token Rotation·재사용 탐지·로그아웃 | 동작 |
| `prompt=login`: Keycloak SSO 세션이 남아 있어도 비밀번호 재입력 | 동작 ("Please re-authenticate") |
| 기기에서 `adb reverse`로 `localhost:8080` 접속 (Keycloak `iss`·Callback 주소 변경 없음) | 동작 |

### 설계와 다르게 처리한 것

| 설계 | 실제 | 이유 |
|---|---|---|
| Android App Link / iOS Universal Link로 앱 복귀 | **커스텀 스킴 `pmart-employee://`** | App Link는 인증서가 있는 HTTPS 도메인과 `assetlinks.json` 검증이 필요. LAN 테스트 환경에는 없음. 대신 ticket을 앱의 `app_verifier`에 결속해 가로채기를 무력화 |
| (설계에 없음) 앱 로그아웃 시 Keycloak SSO 세션 | 끝내지 않고 `prompt=login`으로 매번 재인증 | Keycloak 로그아웃 리다이렉트에 커스텀 스킴을 등록하려면 Realm 설정 변경이 필요. Realm은 최초 Import만 되므로 단순한 방법 선택 |

### 알게 된 점

1. **에뮬레이터 Chrome 첫 실행 화면**: 새 에뮬레이터는 Chrome 초기 설정·약관 화면이 먼저 떠서 Custom Tab 로그인을 가로막음. 자동화 테스트 환경에서는 미리 처리해 둬야 함.
2. **로그인 중 앱 프로세스가 죽으면 로그인 결과를 잃음**: Custom Tab이 열린 상태에서 앱이 종료되면, 로그인 후 커스텀 스킴으로 돌아와도 결과를 받을 앱이 없어 홈 화면으로 감. 서버에는 쓰이지 않은 ticket이 남지만 60초 뒤 만료·정리되어 위험은 없음. 사용자는 다시 로그인하면 됨.
3. **`adb reverse`가 주소 문제를 단순하게 해결**: 기기의 `localhost:8080`을 PC로 연결하면 Keycloak `iss`, Callback 주소, Realm Redirect URI를 바꾸지 않고 브라우저·앱이 같은 주소를 씀. 단, USB(또는 에뮬레이터) 연결이 필요하며 같은 LAN의 다른 기기에서 무선으로 쓰려면 `PUBLIC_BASE_URL`을 LAN 주소로 바꾸고 Realm Redirect URI도 함께 바꿔야 함.
4. **Flutter 플러그인 Kotlin 경고**: 빌드 시 일부 플러그인이 Flutter의 새 Kotlin 빌드 방식으로 아직 옮겨가지 않았다는 경고가 나옴. 현재 빌드에는 영향 없음.

## 2026-11-11 — IAM 계정 발급 방식 변경 (D21)

### 변경 내용

Keycloak을 사용하는 목적은 **직원 IAM 계정 발급**이다. 이전 구현은 사번을 Keycloak username으로 쓰고 이메일(`사번@pmart.test`)을 자동으로 만들어 이 목적이 드러나지 않았다.

| 항목 | 이전 | 변경 후 |
|---|---|---|
| Keycloak username (로그인 ID) | 사번 | **IAM 계정 ID** `계정명@pmart.test` |
| Keycloak email | `사번@pmart.test` 자동 생성 | IAM 계정 ID와 같음 |
| 사번 | 로그인 ID 겸 인사 정보 | 인사 정보로만 사용 (PMart JWT `sub`는 그대로 사번) |
| 계정명 결정 | — | 인사팀이 등록 화면에서 입력, 형식·중복 검사 |

### 결과 요약

- `scripts/e2e-test.sh`: **73개 항목 통과** (IAM 계정 항목 8개 추가). 마지막 연속 5회 실행 모두 통과
- 확인 항목: 발급된 IAM 계정 ID, Keycloak username·email이 IAM 계정 ID와 같음(Keycloak DB 직접 확인), IAM 계정 ID 중복 거부(409), 형식 오류 거부(400), IAM 계정 ID로 로그인, `/me`에 IAM 계정 ID·사번, **사번으로는 로그인 불가**
- 기존 데이터: 테스트용 인사팀 계정은 부팅 시 새 IAM 계정(`hr.admin@pmart.test`)을 만들고 사번 매핑을 옮긴 뒤, 사번으로 로그인하던 이전 Keycloak 계정을 비활성화함. 이전에 등록한 테스트 직원은 IAM 계정 ID가 없는 "이전 방식"으로 남아 있음

### 백오피스 직원 등록 실패 (사용자 보고)

- 증상: 사번 `EMP0003`으로 직원 등록 시 실패. 화면에는 원인이 표시되지 않음
- 원인 1: 사번 형식을 "영문 2~3자 + 숫자 정확히 6자리"로 근거 없이 엄격하게 정해 둠 → **영문 대문자·숫자 3~20자**로 완화 (사번은 인사 정보일 뿐)
- 원인 2: Spring Boot 기본 오류 응답에 입력값 오류 내용이 빠져 화면에 원인이 안 보임 → `@RestControllerAdvice`로 항목별 한국어 메시지를 `ProblemDetail.detail`에 담아 응답
- 교훈: 설계에 없는 입력 규칙을 임의로 정하지 말 것. 검증 실패는 항상 사용자에게 이유를 보여줄 것
- 함정: Git Bash에서 `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh`를 실행하면 경로가 Windows 경로로 바뀌어 실패함. `MSYS_NO_PATHCONV=1` 필요

### 앱 로그인 아이디 칸 입력 불가 (사용자 보고)

- 증상: Flutter 앱에서 다시 로그인할 때 Keycloak 화면의 Username 칸에 입력이 안 됨
- 원인: 앱 로그인에 `prompt=login`을 썼음. 휴대폰 브라우저에 이전 Keycloak 세션이 남아 있으면 Keycloak은 "Please re-authenticate" 화면을 띄우고 **아이디를 이전 계정으로 고정한 채 비밀번호만 받음**. 다른 IAM 계정으로는 로그인할 수 없었음 (아이디 옆 ↻ 아이콘으로만 바꿀 수 있음)
- 수정: `prompt=login` 제거. 앱 로그인 Callback에서 Code 교환 직후 Employee Auth Service가 Keycloak logout 엔드포인트를 서버 간 호출로 불러 **Keycloak 세션을 종료**. 앱은 PMart Refresh Token으로 로그인을 유지하므로 Keycloak 세션이 필요 없음
- 검증: 앱 로그인 후 같은 브라우저 쿠키로 다시 로그인 화면을 열면 아이디 칸이 비어 있고 "re-authenticate"가 없음을 스크립트로 확인
- 교훈: 에뮬레이터 확인 때 "Please re-authenticate"와 아이디 고정을 보고 `prompt=login`이 동작한다고만 판단했고, 다른 계정으로 로그인하는 경우를 확인하지 않았음

### 해결: 앱 연속 로그인 간헐 실패 → 테스트 스크립트 버그

- 앞서 "원인 미확인"으로 기록했던 간헐 실패의 원인은 **테스트 스크립트**였음
- 스크립트가 무작위로 만든 `app_verifier`가 `-`로 시작하면 `node -e ... "$verifier"`에서 node가 이를 **명령 옵션으로 해석**(`bad option: -3w9...`)해 `app_challenge` 계산에 실패 → 로그인 시작 400 → 이후 항목 연쇄 실패. Base64URL 64개 문자 중 하나가 `-`라 확률적으로 발생
- 수정: `node -e ... -- "$verifier"`. `-`로 시작하는 verifier를 강제로 쓰게 한 실행과 일반 실행 3회 모두 73개 통과
- 서버·Keycloak 문제가 아니었음. "Keycloak 이벤트 로그를 켜고 다시 확인해야 함"이라는 이전 기록은 취소

### 남은 단계

- 11단계 LAN 메일
- 11단계 LAN 메일
- 자동화 테스트(JUnit, Testcontainers) 추가. Initializr가 만든 기본 테스트(`contextLoads`)는 DB 없이 실패하므로 삭제한 상태
