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

### 남은 단계

- 10단계 Flutter 앱 (미결정 #3)
- 11단계 LAN 메일
- 자동화 테스트(JUnit, Testcontainers) 추가. Initializr가 만든 기본 테스트(`contextLoads`)는 DB 없이 실패하므로 삭제한 상태
