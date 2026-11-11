# 03. 직원 로그인 흐름 (Authorization Code + PKCE)

## 참여자

| 참여자 | 역할 |
|---|---|
| 직원 Flutter 앱 / 인사팀 백오피스(React SPA) | 로그인 시작, 결과 수신 |
| 시스템 브라우저 (Flutter) | Android Custom Tabs, iOS ASWebAuthenticationSession |
| Spring Cloud Gateway | 외부 요청 프록시 |
| Employee Auth Service | **OIDC Client** |
| Keycloak | **Authorization Server / OpenID Provider** |
| Employee Service / 직원 DB | 직원 매핑·상태·권한 확인 |

## 전체 시퀀스 — 합의한 설계

```text
[1. 로그인 시작]
직원 앱 ── GET /employee-auth/oauth2/start ──> Gateway ──> Employee Auth Service
    Employee Auth Service:
      ├─ state, nonce 생성
      ├─ PKCE code_verifier 생성, code_challenge(S256) 계산
      ├─ 인증 트랜잭션을 PostgreSQL에 저장 (만료시간, 앱 연계 정보 포함)
      └─ Keycloak Authorization URL 반환

[2. Keycloak 로그인 페이지]
브라우저(Flutter: 시스템 브라우저 / 인사팀 백오피스: 현재 브라우저)
  ──> Gateway (/iam/**) ──> Keycloak Authorization Endpoint
      ├─ 기존 SSO 세션 확인
      ├─ 필요 시 로그인 HTML/CSS/JS 응답 (Gateway 경유)
      └─ 사번·비밀번호(+MFA) 인증 (폼 제출도 Gateway 경유)

[3. Authorization Code 반환]
Keycloak ── 302 Location: https://api.pmart.test/employee-auth/callback?code=...&state=...
  ──> Gateway ──> 브라우저
브라우저 ── GET /employee-auth/callback?code=...&state=... ──> Gateway ──> Employee Auth Service
      └─ state로 PostgreSQL의 인증 트랜잭션 조회·검증 후 삭제(재사용 방지), code 수신

[4. Code → 토큰 교환 (백채널)]
Employee Auth Service ── POST Keycloak /token ──> Keycloak
      (grant_type=authorization_code, code, redirect_uri, code_verifier, Client 인증)
Keycloak ── ID Token + Access Token (+ Refresh Token) ──> Employee Auth Service
      ├─ ID Token 검증 (서명, iss, aud, exp, nonce 등)
      ├─ Keycloak sub → PMart 직원 매핑
      ├─ 재직·계정 상태, 업무 권한 확인
      └─ PMart 자체 Access/Refresh Token 발급

[5. 직원 앱으로 결과 전달]
Flutter: 302 App Link → 앱 복귀 → 일회성 ticket을 Gateway 경유로 교환 → PMart 토큰 수령
백오피스: Employee Auth Service가 Set-Cookie(PMart JWT) + 302 Redirect → 인사팀 백오피스(React SPA)

[6. 업무 API 호출]
Flutter 앱 ── Authorization: Bearer <PMart Employee Access Token> ──> Gateway ──> 업무 서비스
인사팀 백오피스 ── Cookie: <PMart JWT 쿠키> (브라우저가 자동 전송) ──> Gateway
      └─ Gateway가 쿠키의 JWT 검증 후 Authorization: Bearer 헤더로 바꿔 업무 서비스에 전달
```

> **핵심**: Authorization Code는 Keycloak이 **브라우저에 302 Redirect로** 전달합니다. 브라우저가 Gateway를 거쳐 Callback URL로 새 요청을 보내고, Code를 토큰으로 교환하는 주체는 **Employee Auth Service**입니다. 직원 앱은 Keycloak Code를 직접 다루지 않습니다.

## PKCE와 Grant Type

- Grant Type은 **`authorization_code`** 입니다. PKCE는 Grant Type이 아니라 Authorization Code Flow의 보안 확장입니다.
- `code_verifier`: Employee Auth Service가 생성·보관 (브라우저에 노출하지 않음)
- `code_challenge = BASE64URL(SHA256(code_verifier))`, `code_challenge_method=S256`
- Token Endpoint에는 원래 `code_verifier`와 Client 인증을 함께 제출합니다.
- `state`: 요청/응답 결속, CSRF 방어
- `nonce`: 인증 요청과 ID Token 결속 (재전송 방지)

## Authorization URL 예시

```http
GET /iam/realms/pmart-employees/protocol/openid-connect/auth
    ?response_type=code
    &client_id=pmart-employee-auth
    &redirect_uri=https%3A%2F%2Fapi.pmart.test%2Femployee-auth%2Fcallback
    &scope=openid%20profile
    &state=RANDOM_STATE
    &nonce=RANDOM_NONCE
    &code_challenge=BASE64URL_SHA256_VERIFIER
    &code_challenge_method=S256
```

`/iam` 접두사는 Gateway 경유 배포안 기준입니다. 실제 경로·호스트는 Keycloak/Gateway 구성과 일치시킵니다.

## Token Endpoint 호출 예시

```http
POST /realms/pmart-employees/protocol/openid-connect/token
Authorization: Basic <base64(client_id:client_secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=AUTH_CODE
&redirect_uri=https%3A%2F%2Fapi.pmart.test%2Femployee-auth%2Fcallback
&code_verifier=ORIGINAL_VERIFIER
```

Employee Auth Service ↔ Keycloak 간 **서버 간 백채널 통신**이므로 **Gateway를 경유하지 않고** Compose 내부 네트워크에서 Keycloak을 직접 호출합니다. `redirect_uri`는 Authorization 요청 때와 정확히 같아야 합니다.

## Flutter 앱과 인사팀 백오피스의 차이

웹 클라이언트는 **인사팀 백오피스 React SPA**(Vite + Tailwind CSS + daisyUI) 하나뿐입니다.

| 항목 | Flutter | React SPA (인사팀 백오피스) |
|---|---|---|
| 로그인 시작 | Gateway → Employee Auth Service | 동일 |
| 로그인 페이지 | 앱이 연 시스템 브라우저 | 현재 웹 브라우저 |
| Keycloak 로그인 페이지 제공 | Keycloak → Gateway → 브라우저 | 동일 |
| OIDC Client | Employee Auth Service | 동일 |
| Code Callback | Gateway → Employee Auth Service | 동일 |
| 로그인 후 복귀 | Android App Link / iOS Universal Link | 백오피스 URL Redirect |
| PMart 토큰 수령 | 일회성 ticket 교환 (권장) | **`Set-Cookie`로 PMart JWT 수령 (확정)** |
| API 호출 시 토큰 전달 | `Authorization: Bearer` 헤더 | 브라우저가 쿠키 자동 전송 |

### Flutter 주의 사항

- **임베디드 WebView 로그인은 비권장**입니다. 시스템 브라우저를 사용합니다.
- App Link는 **로그인 완료 후 앱으로 돌아오는 수단**입니다. Keycloak Authorization Code를 앱에 전달할 필요가 없습니다.

### 인사팀 백오피스: Set-Cookie 방식 — 확정 (D15)

```http
HTTP/1.1 302 Found
Location: /backoffice/
Set-Cookie: PMART_EMP_AT=<PMart JWT>; HttpOnly; SameSite=Lax; Path=/; Max-Age=900
```

| 항목 | 내용 |
|---|---|
| 발급 시점 | Employee Auth Service가 Callback 처리(ID Token 검증, PMart JWT 발급)를 마친 직후 |
| 쿠키 이름 (예시) | `PMART_EMP_AT` |
| `HttpOnly` | 켬. JavaScript가 토큰을 읽지 못해 XSS로 토큰이 유출되지 않음 |
| `SameSite` | `Lax`. 다른 사이트에서 보내는 POST 요청에는 쿠키가 실리지 않음 |
| `Secure` | HTTPS 접속 시 켬. 브라우저는 `localhost` 외의 HTTP 주소에서 `Secure` 쿠키를 저장하지 않으므로 LAN에서 HTTP로 테스트할 때는 주의 |
| `Path` | `/` |
| `Max-Age` | Access Token 수명과 같은 900초(15분) |
| 로그아웃 | 같은 쿠키를 `Max-Age=0`으로 내려 삭제 |

- SPA는 토큰을 직접 다루지 않습니다. `localStorage`·`sessionStorage`에 저장하지 않으며, 사용자 정보는 API(예: `/employee-auth/me`)로 조회합니다.
- 쿠키는 Gateway 주소에 대해 설정됩니다. 백오피스는 자기 Nginx 컨테이너 주소(Origin)에서 제공되고 API는 Gateway 주소로 호출하므로, **Gateway에 CORS를 적용**하고 SPA는 `fetch(..., { credentials: 'include' })`(axios는 `withCredentials: true`)로 요청해야 쿠키가 실립니다. CORS 설정은 [05 Gateway 라우팅](05-gateway-routing.md#cors--확정-d16)을 참고하세요.
- 쿠키 인증은 브라우저가 자동으로 쿠키를 보내므로 **CSRF 방어를 Gateway에서 적용**합니다. SPA는 `GET /csrf`로 토큰을 받아 상태를 바꾸는 요청에 `X-XSRF-TOKEN` 헤더로 보냅니다. 상세 내용은 [05 Gateway 라우팅](05-gateway-routing.md#csrf-방어--확정-d17)을 참고하세요.
- Refresh Token을 어떻게 다룰지는 [미결정 #6](../open-questions.md)에서 정합니다.

### Flutter 앱: 커스텀 스킴 + 일회성 ticket — 확정 (D20)

```text
1. 앱: app_verifier(무작위 32바이트) 생성, app_challenge = BASE64URL(SHA256(app_verifier))
2. 앱 → 시스템 브라우저: GET /employee-auth/oauth2/start?client=mobile&app_challenge=...
   Employee Auth: 로그인 트랜잭션에 client=mobile, app_challenge 저장 → Keycloak으로 이동 (prompt=login)
3. Keycloak 로그인 → Callback (백오피스와 같은 Code 교환·ID Token 검증·직원 확인)
4. Employee Auth: 일회성 ticket 생성(해시 저장, 60초) → 302 pmart-employee://login-callback?ticket=...
   (토큰·쿠키는 내려주지 않음)
5. 앱 → POST /employee-auth/mobile/token {ticket, appVerifier}
   Employee Auth: ticket 꺼내면서 삭제 → SHA256(appVerifier) == app_challenge 확인 → Access·Refresh Token(JSON)
6. 앱: 토큰을 보안 저장소에 보관, API는 Authorization: Bearer
7. 갱신: POST /employee-auth/mobile/refresh {refreshToken}  /  로그아웃: POST /employee-auth/mobile/logout {refreshToken}
```

| 항목 | 내용 |
|---|---|
| 앱 복귀 | 커스텀 스킴 `pmart-employee://login-callback`. LAN 테스트 환경에는 HTTPS 도메인이 없어 App Link/Universal Link를 쓸 수 없음 |
| 커스텀 스킴 가로채기 대비 | ticket은 앱의 `app_verifier`가 있어야 교환 가능. 교환 시도에 실패해도 ticket은 삭제됨(추측 시도 방지) |
| ticket | 무작위 256bit, DB에는 SHA-256 해시만, 60초, 단일 사용 |
| 토큰 API | `/employee-auth/mobile/**`: JSON 본문으로만 자격 증명을 받고 쿠키를 쓰지 않으므로 Gateway CSRF 검사 제외 |
| Refresh Token | 백오피스와 같은 정책(D19). 앱에는 JSON으로 전달 |
| 토큰 보관 | `flutter_secure_storage` (Android Keystore / iOS Keychain) |
| 로그인 화면 | `prompt=login`: 앱 로그아웃은 Keycloak SSO 세션을 끝내지 않으므로 매번 비밀번호를 다시 받음 |
| 기기 → Gateway | 실기기·에뮬레이터 모두 `adb reverse tcp:8080 tcp:8080` 후 `http://localhost:8080`. Android는 `localhost`·`10.0.2.2`에만 평문 HTTP 허용(`network_security_config.xml`) |

### 일회성 ticket 요구사항

일회성 ticket은 OAuth 표준 Authorization Code와 **다른, PMart 내부 코드**입니다.

- 짧은 만료시간 (수십 초 ~ 수 분 수준)
- 단일 사용 (사용 즉시 무효화, 재사용 시 거부)
- 앱의 로그인 시작 트랜잭션과 **암호학적 결속** (앱이 보유한 verifier/nonce 등을 교환 시 함께 제출)
- **ticket만 아는 제3자가 토큰으로 교환할 수 있어서는 안 됩니다.**
- PMart Access/Refresh Token을 딥링크나 URL 쿼리에 직접 싣지 않습니다.

## Silent Authentication

`prompt=none`은 기존 Keycloak SSO 세션이 있을 때 화면 없이 인증하도록 요청하는 기능입니다. **최초 로그인의 비밀번호 입력을 대체하지 않습니다.**
