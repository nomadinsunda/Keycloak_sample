# 05. Gateway와 Keycloak 통신

## Gateway 구현 방식 — 확정

- Gateway는 **Spring Boot 4.x**로 만들고, Spring Cloud Gateway는 Boot 4와 호환되는 버전을 사용합니다.
- Spring Cloud Gateway는 **WebFlux 기반(reactive)** 으로 만들고, 내장 서버로 **Netty**를 사용합니다.
- 서블릿(Tomcat) 기반의 Spring Cloud Gateway Server Web MVC 방식은 사용하지 않습니다. Gateway에 `spring-boot-starter-web` 계열 의존성을 넣지 않습니다.
- Gateway의 JWT 검증·접근 정책은 reactive Spring Security(`SecurityWebFilterChain`, `ReactiveJwtDecoder` 등)로 작성합니다.

## Keycloak은 Gateway 뒷단 — 확정

Keycloak 서버는 API Gateway 뒷단에 두고, **Gateway가 Keycloak 서버로 HTTP 요청을 라우팅**합니다. 브라우저는 Gateway `/iam/**`를 거쳐서만 Keycloak에 접속하며, Keycloak 포트는 외부에 공개하지 않습니다.

### 서버 간 호출은 Gateway 경유 금지 — 확정

- Gateway의 Keycloak 라우트는 **브라우저 요청 전용**입니다.
- Employee Auth Service → Keycloak(Token Endpoint, JWKS, 부팅 시 테스트 계정 생성), Employee Service → Keycloak Admin REST API 호출은 **Gateway를 거치지 않고** Compose 내부 네트워크에서 `http://keycloak:8080`으로 직접 호출합니다.
- Keycloak Admin REST API와 관리 콘솔은 Gateway 라우트로 노출하지 않습니다.
- **Employee Auth Service의 JWKS도 내부 직접 호출**입니다. Gateway와 샘플 업무 API는 `http://employee-auth:8080`의 JWKS 엔드포인트를 Compose 내부 네트워크에서 직접 호출하며, JWKS 엔드포인트는 Gateway로 노출하지 않습니다.

### Keycloak 라우트 예시

```yaml
# Gateway application.yml (예시)
# 속성 접두사는 사용하는 Spring Cloud Gateway 버전 문서로 확인
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: keycloak
              uri: http://keycloak:8080      # Compose 서비스 이름
              predicates:
                - Path=/iam/**
            - id: employee-auth
              uri: http://employee-auth:8080
              predicates:
                - Path=/employee-auth/**
```

- Keycloak의 `http-relative-path`를 `/iam`으로 설정하면 Gateway에서 경로 접두사를 지우지 않고 그대로 넘깁니다.
- Gateway가 `X-Forwarded-*` 헤더를 붙여 넘기고, Keycloak은 이 헤더를 신뢰하도록 프록시 설정을 맞춥니다. 그래야 Keycloak이 만드는 로그인 폼 주소, Redirect 주소, `iss`가 Gateway 주소로 나옵니다.
- Keycloak 로그인 경로는 PMart JWT 없이 접근할 수 있어야 하므로 Gateway 인증 필터에서 제외합니다.

## 안 A: Gateway 경유 로그인 — 확정

```text
직원 앱       → Gateway                         → Employee Auth Service  (로그인 시작)
브라우저      → Gateway (/iam/**)               → Keycloak              (로그인 HTML/폼)
Keycloak     → Gateway                         → 브라우저               (302 Redirect)
브라우저      → Gateway (/employee-auth/callback) → Employee Auth Service
Employee Auth Service ── 직접 서버 간 통신 ──> Keycloak /token
```

- Gateway는 Keycloak의 HTML/CSS/JS, 로그인 폼, 쿠키, 302 응답을 **HTTP 프록시**만 합니다.
- Gateway는 비밀번호를 검증하지 않고, Authorization Code를 토큰으로 교환하지도 않습니다.

## 안 B: 별도 IAM 호스트 — 채택하지 않음

```text
직원 앱      → api.pmart.test → Spring Cloud Gateway → PMart API
직원 브라우저 → iam.pmart.test → Keycloak (또는 전용 Reverse Proxy)
```

Keycloak을 Gateway 뒷단에 두기로 확정했으므로 이 방식은 쓰지 않습니다.

## 경로 정책 (예시)

| 경로 | PMart JWT 필요 | 대상 | 비고 |
|---|---|---|---|
| `/employee-auth/oauth2/start` | 아니오 | Employee Auth Service | 로그인 시작 |
| `/employee-auth/callback` | 아니오 | Employee Auth Service | Keycloak Redirect 수신 |
| `/employee-auth/ticket` 등 (가칭) | 아니오 (ticket + 앱 verifier로 인증) | Employee Auth Service | Flutter 토큰 교환 |
| `/iam/realms/pmart-employees/**` | 아니오 | Keycloak | 로그인 화면·폼 |
| `/iam/admin/**` | **외부 노출 금지** | — | Admin API·관리 콘솔 비공개 |
| 그 외 직원 업무 API | 예 | 업무 MSA | Employee Auth `iss`·`aud`·권한 검증 |

- Keycloak Admin REST API는 공개 로그인 경로에 함께 노출하지 않습니다. Employee Service는 내부망에서 직접 호출합니다.

## PMart JWT 읽는 위치 — 확정 (D15)

| 클라이언트 | Gateway가 JWT를 읽는 위치 |
|---|---|
| 인사팀 백오피스 (React SPA) | 쿠키 (`PMART_EMP_AT`) |
| Flutter 앱 | `Authorization: Bearer` 헤더 |

- Gateway는 헤더를 먼저 확인하고, 없으면 쿠키에서 JWT를 읽습니다. reactive Spring Security에서는 Bearer 토큰 변환기(`ServerAuthenticationConverter`)를 직접 구성해야 합니다.
- 검증을 통과하면 하위 서비스(Employee Service, 샘플 업무 API)로 넘길 때 `Authorization: Bearer <JWT>` 헤더로 바꿔 전달합니다. 하위 서비스는 헤더만 읽습니다.

## CORS — 확정 (D16)

인사팀 백오피스(React SPA)는 자기 Nginx 컨테이너 주소에서 제공되고, API는 Gateway 주소로 호출합니다. Origin이 다르므로 **Gateway에서 CORS를 적용**합니다.

| 항목 | 값 |
|---|---|
| 적용 위치 | **Gateway에서만** 적용. Employee Auth Service, Employee Service, 샘플 업무 API는 CORS 헤더를 붙이지 않음(헤더 중복 방지) |
| 허용 Origin | 백오피스 주소만 명시 (환경변수 예: `BACKOFFICE_ORIGIN=http://localhost:3000`). `*` 사용 금지 |
| 자격 증명 | `allowCredentials=true` (PMart JWT 쿠키 전송에 필요). 이 설정에서는 Origin에 `*`를 쓸 수 없음 |
| 허용 메서드 | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` |
| 허용 요청 헤더 | `Content-Type`, CSRF 방어용 헤더(예: `X-XSRF-TOKEN`) |
| Preflight 캐시 | `maxAge` 3600초 |
| Preflight 인증 | `OPTIONS` 요청은 JWT 없이 통과해야 함 |

- **구현 방식**: reactive Spring Security의 `SecurityWebFilterChain`에 `cors()`와 `CorsConfigurationSource`로 설정합니다. 이렇게 하면 인증 필터보다 먼저 CORS가 처리되어 preflight가 401로 막히지 않습니다.
- **대상 경로**: SPA가 `fetch`/`axios`로 호출하는 API 경로(`/employee-auth/**`, Employee Service, 샘플 업무 API)입니다. `/iam/**`(Keycloak 로그인 화면)과 `/employee-auth/callback`은 브라우저가 페이지 이동으로 접근하므로 CORS 대상이 아닙니다.
- **SPA 요청**: `fetch(url, { credentials: 'include' })` 또는 axios `withCredentials: true`로 보내야 쿠키가 실립니다.
- **SameSite 주의**: 쿠키가 `SameSite=Lax`이므로 백오피스와 Gateway는 **같은 호스트(포트만 다름)** 에 있어야 쿠키가 전송됩니다. 예: `http://localhost:3000` ↔ `http://localhost:8080`은 가능합니다. 호스트가 다르면 `SameSite=None; Secure`(HTTPS 필수)로 바꿔야 합니다.

## CSRF 방어 — 확정 (D17)

인사팀 백오피스는 PMart JWT를 쿠키로 보내므로, 다른 사이트가 사용자 몰래 요청을 보내는 CSRF 공격을 막아야 합니다. **Gateway에서 CSRF 토큰(Double Submit Cookie) 방식**으로 방어합니다.

### 흐름

```text
1. SPA 시작 시 (또는 로그인 직후)
   SPA ── GET /csrf (credentials: 'include') ──> Gateway
   Gateway ── Set-Cookie: XSRF-TOKEN=<토큰> + 응답 본문 {"token": "<토큰>"} ──> SPA
   SPA는 응답 본문의 토큰을 메모리에 보관

2. 상태를 바꾸는 요청 (POST/PUT/PATCH/DELETE)
   SPA ── X-XSRF-TOKEN: <토큰> 헤더 + 쿠키(PMART_EMP_AT, XSRF-TOKEN) ──> Gateway
   Gateway: 헤더 토큰과 쿠키 토큰이 맞는지 확인 → 맞으면 하위 서비스로 전달, 틀리면 403
```

- 공격자 사이트는 쿠키는 자동으로 실을 수 있어도 `X-XSRF-TOKEN` 헤더에 들어갈 값을 알 수 없으므로 요청이 거부됩니다.
- 토큰을 응답 본문으로 주는 이유: 백오피스와 Gateway는 Origin이 다르므로, SPA가 Gateway 쿠키를 JavaScript로 읽는 방식에 기대지 않기 위해서입니다. 본문은 CORS 허용 Origin(백오피스)만 읽을 수 있습니다.

### 적용 규칙

| 항목 | 내용 |
|---|---|
| 적용 위치 | **Gateway에서만** 적용 (reactive Spring Security `csrf()`, 쿠키 기반 토큰 저장소). 하위 서비스는 Bearer 헤더만 받으므로 CSRF를 끔 |
| 검사 대상 | **PMart JWT 쿠키로 인증하는** `POST`, `PUT`, `PATCH`, `DELETE` 요청 (예: 직원 등록, 로그아웃) |
| 검사 제외 | `GET`·`HEAD`·`OPTIONS`, `Authorization: Bearer` 헤더로 인증하는 요청(Flutter 앱), `/iam/**`(Keycloak 로그인 폼은 Keycloak이 자체 CSRF 방어를 하므로 Gateway가 검사하면 로그인이 깨짐), `/employee-auth/callback` |
| 토큰 발급 경로 | `GET /csrf` (Gateway, JWT 없이 접근 가능) |
| 헤더 이름 | `X-XSRF-TOKEN` (CORS 허용 헤더에 포함) |
| 실패 응답 | `403 Forbidden` → SPA는 `/csrf`로 토큰을 다시 받고 한 번 재시도 |
| 보조 방어 | `SameSite=Lax` 쿠키, 상태 변경 요청의 `Origin` 헤더가 `BACKOFFICE_ORIGIN`과 같은지 확인 |

- GET 요청은 데이터를 바꾸지 않도록 API를 설계합니다. GET으로 상태를 바꾸면 CSRF 방어가 무의미해집니다.
- Spring Security 7의 SPA용 CSRF 설정(토큰 마스킹 처리 등)은 공식 문서로 확인한 뒤 구현합니다.

## 설정 정합성 체크

- Keycloak `hostname`, `http-relative-path`(예: `/iam`), 프록시 헤더(`X-Forwarded-*` / `Forwarded`) 설정과 Gateway의 경로 처리(접두사 유지/제거)를 서로 일치시킵니다.
- Keycloak이 생성하는 URL(로그인 폼 action, Redirect, `iss`)이 외부에서 보이는 주소와 같아야 합니다. 어긋나면 ID Token `iss` 검증이 실패합니다.
- 외부 요청은 HTTPS를 권장합니다. 내부 프록시 구간의 HTTP 사용 여부는 LAN 신뢰 경계에 따라 결정합니다.
