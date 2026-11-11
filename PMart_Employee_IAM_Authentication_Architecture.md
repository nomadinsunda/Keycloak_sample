# PMart 직원 IAM 및 인증 아키텍처 설계 정리

> 작성 기준: 2026-11-11 대화 전체의 설계 논의  
> 문서 성격: 국비 훈련생 PMart 프로젝트의 **합의된 방향·권장 구현·미결정 사항**을 구분한 설계 기록  
> 주의: 아래 예시 도메인, IP, 포트, Client ID는 실제 배포 전에 확인해야 하는 예시입니다.

## 프로젝트 목적과 구현 범위

**이 저장소는 PMart 프로젝트에 Keycloak을 적용할지 검증하기 위한 테스트(샘플) 프로젝트입니다.** PMart 본 프로젝트의 코드베이스가 아닙니다.

- 이 문서의 Keycloak 기반 직원 인증 구조가 실제로 동작하는지 작은 규모로 구현해 확인합니다.
- 검증 결과를 근거로 PMart 본 프로젝트에 Keycloak을 적용할지, 어떤 방식으로 적용할지 판단합니다.
- 코드는 **검증에 필요한 최소 범위**로 작성합니다. 고객·라이더 인증과 업무 기능은 구현하지 않으며, 업무 MSA는 JWT 검증을 확인할 수 있을 정도의 샘플 API로 대신합니다.
- 검증 중 설계와 다른 결과(동작하지 않음, 버전 제약, 설정 충돌 등)가 나오면 문서에 기록합니다. 그 기록이 이 프로젝트의 핵심 결과물입니다.

### 구현하지 않는 것: Customer Auth Service, Rider Auth Service

- **고객용 Customer Auth Service와 라이더용 Rider Auth Service는 이 저장소에서 구현하지 않습니다.** 두 서비스는 PMart 본 프로젝트에 이미 있거나 따로 개발되며, Keycloak과 관계가 없습니다.
- 이 저장소에서 구현하는 Auth Service는 **Employee Auth Service 하나뿐**입니다.
- 이 문서에 고객·라이더 Auth가 나오는 이유는 PMart 전체 구조(발급자 3개)와 서비스 분리 원칙을 설명하기 위해서입니다. 구현 대상이라는 뜻이 아닙니다.
- 다른 발급자의 토큰이 거부되는지 확인하는 테스트(교차 접근 거부)에는 실제 서비스 대신 **테스트용 키로 서명한 고객·라이더 토큰**을 사용합니다.

### 기술 버전과 실행 환경 — 확정

- **Spring Boot 4.x**: 모든 Spring Boot 서비스(Employee Auth Service, Employee Service, Gateway, 샘플 업무 API)는 Spring Boot 4.x로 만듭니다. Spring Security, Spring Cloud 등 관련 의존성도 Boot 4와 호환되는 버전을 씁니다. 이 문서의 의존성 예시(7.2절 등)는 Boot 4 기준 공식 문서로 starter 이름과 설정 방식을 확인한 뒤 사용합니다.
- **인사팀 백오피스는 React SPA**: 인사팀이 사용하는 앱(8.1절의 PMart Backoffice)은 React SPA로 만듭니다. 빌드 도구는 **Vite**, 스타일은 **Tailwind CSS + daisyUI**이며 둘 다 작업 시점의 최신 안정 버전을 확인해 고정합니다. 웹 클라이언트는 인사팀 백오피스 하나뿐이며, 별도의 "React 직원 웹"은 만들지 않습니다. 로그인이 끝나면 Employee Auth Service가 **PMart JWT를 `Set-Cookie`로 전달**합니다(5절). 백오피스와 Gateway의 Origin이 다르므로 **CORS는 Gateway에서만 적용**합니다(허용 Origin은 백오피스 주소만 명시, `allowCredentials=true`). 쿠키 인증을 쓰므로 **CSRF 방어도 Gateway에서** Double Submit Cookie 방식으로 적용합니다(`GET /csrf`로 토큰 발급, 상태 변경 요청에 `X-XSRF-TOKEN` 헤더 검사, Bearer 헤더 요청과 `/iam/**`는 제외).
- **Keycloak은 API Gateway 뒷단**: **Gateway가 Keycloak 서버로도 HTTP 요청을 라우팅**하며(`/iam/**` → Keycloak), 브라우저는 항상 이 경로를 거쳐 Keycloak에 접속합니다. 별도 IAM 호스트로 직접 노출하지 않으며, Keycloak 포트는 외부에 공개하지 않습니다(6절). 단, **Employee Auth Service·Employee Service가 Keycloak을 호출할 때는 Gateway를 경유하지 않고** 내부 네트워크에서 직접 호출합니다. Employee Auth Service의 **JWKS도 내부 직접 호출**이며 Gateway로 노출하지 않습니다(7.3절).
- **Employee Auth Service, Employee Service, 샘플 업무 API는 Spring RESTful API 서버**: Spring Boot 4 기반으로, Spring Web MVC `@RestController`와 JSON 요청·응답을 사용합니다. 화면(HTML)은 만들지 않습니다.
- **테스트용 인사팀 계정**: 테스트 용도로, 인사팀 백오피스의 계정과 비밀번호는 **Employee Auth Service가 부팅할 때 생성**합니다. 부팅 후 바로 이 계정으로 로그인할 수 있습니다(8.4절).
- **API Gateway는 Spring Boot 4 + Netty**: Gateway도 Spring Boot 4.x로 만듭니다. Spring Cloud Gateway는 WebFlux 기반(reactive)으로 만들고 내장 서버로 Netty를 사용합니다. 서블릿(Tomcat) 기반의 Gateway Server Web MVC 방식은 쓰지 않으며, Gateway 보안 설정도 reactive API로 작성합니다.
- **PostgreSQL**: 데이터베이스가 필요한 경우 PostgreSQL을 사용합니다(Keycloak, Employee Service, Employee Auth Service, Roundcube 등). H2, MySQL 등 다른 DB는 쓰지 않으며, 구성 요소별로 DB와 전용 계정을 나눕니다. 로그인 트랜잭션(`state`·`nonce`·PKCE)도 PostgreSQL에 저장하며 Redis는 쓰지 않습니다.
- **Docker Desktop + Docker Compose**: **Flutter 앱을 제외한 모든 아티팩트**(Keycloak, PostgreSQL, Gateway, Employee Auth Service, Employee Service, 샘플 업무 API, 인사팀 백오피스(React SPA), Docker Mailserver, Roundcube)는 Docker Desktop에서 Docker Compose로 실행합니다.
- **Flutter 앱**은 Compose 밖(에뮬레이터 또는 실기기)에서 실행하며, 호스트에 공개된 Gateway 포트(호스트 LAN IP, Android 에뮬레이터의 `10.0.2.2` 등)로 접근합니다.
- 브라우저가 보는 Keycloak 주소와 컨테이너 내부 백채널 주소(예: `http://keycloak:8080`)가 다르면 ID Token `iss` 검증이 실패할 수 있으므로 Keycloak `hostname` 설정과 Employee Auth Service의 issuer 설정을 함께 설계합니다.

## 0. 핵심 결론

1. PMart의 **Auth Service는 총 3개**이며, 고객용(Customer Auth), 라이더용(Rider Auth), 직원용(Employee Auth)을 **서로 분리**합니다. 이를 하나의 Auth Service로 통합하지 않습니다.
2. **Keycloak은 직원 전용 IAM 및 최초 신원 인증**에 사용합니다. 고객·라이더는 기존 인증 체계를 유지합니다.
3. 직원용 **Employee Auth Service가 Keycloak의 OAuth 2.0/OIDC Confidential Client**가 됩니다. 직원 Flutter 앱, 인사팀 백오피스(React SPA), Gateway가 Keycloak의 OIDC Client가 되는 것은 아닙니다(현재 선택한 설계 기준).
4. 직원 최초 로그인은 **OIDC Authorization Code Grant + PKCE(S256)**로 설계합니다. `scope=openid`를 사용합니다.
5. Employee Auth Service는 Keycloak의 **ID Token을 검증**하고 PMart의 직원 정보·상태·권한을 확인한 뒤, **PMart 자체 Access Token과 Refresh Token**을 발급합니다. Keycloak이 발급한 Access/Refresh Token은 PMart 업무 API 인증에 사용하지 않습니다.
6. 직원 앱의 외부 API 요청은 **Spring Cloud Gateway**를 통과합니다. **Keycloak 서버도 Gateway 뒷단에 두며**, Keycloak 로그인 페이지는 Gateway의 `/iam/**` 경로로 프록시합니다(확정). 별도 IAM 호스트로 직접 노출하지 않습니다.
7. 직원 계정 발급은 인사팀의 PMart 백오피스 → **Employee Service → Keycloak Admin REST API**로 처리합니다. 직원 인사 DB와 Keycloak IAM DB는 책임을 분리합니다.
8. 국비 훈련생 실습을 위해 **로컬 LAN 전용 SMTP/IMAP 서버와 Roundcube 웹메일**을 도입하는 방향을 논의했습니다. 실제 구현 시 Mailserver·Roundcube·Keycloak을 별도 컨테이너로 운영합니다.

---

## 1. 프로젝트 범위와 시스템 경계

### 1.1 사용자 유형별 인증 분리 — 확정

| 사용자 유형 | 최초 신원 확인 | PMart JWT 발급 주체 | Keycloak 적용 |
|---|---|---|---|
| 고객 | 기존 외부 소셜 로그인 등 | **Customer Auth Service** | 없음 |
| 라이더 | 기존 외부 소셜 로그인 및 라이더 자격 확인 | **Rider Auth Service** | 없음 |
| 직원 | **Keycloak OIDC** | **Employee Auth Service** | 직원 IAM·인증 전용 |

- **직원용 Auth Service가 세 번째 서비스**입니다. 기존 고객·라이더 Auth Service를 직원 인증 서비스에 합치지 않습니다.
- **이 검증 저장소에서는 Employee Auth Service만 구현합니다.** Customer Auth와 Rider Auth는 PMart 전체 구조를 설명하려고 적은 것이며 구현 대상이 아닙니다.
- 각 Auth Service의 JWT 발급자(`iss`), 서명키, 토큰 수명 및 Refresh Token 정책은 독립적으로 운영할 수 있습니다.
- Gateway 및 업무 서비스는 API별로 신뢰할 발급자를 명시해야 합니다.

### 1.2 Keycloak의 책임 — 확정

- 직원 계정(User), 사용자명, 비밀번호 해시 및 자격 증명 관리
- 직원 로그인과 MFA, 중앙 인증 세션 및 SSO
- OIDC ID Token 발급 및 공개키(JWKS) 제공
- 직원용 Realm, Client, 기본 Role/Group 및 인증 정책 관리
- Employee Service의 관리용 서비스 계정에 대한 Admin API 접근 권한 부여

**Keycloak은 PMart 자체 Access/Refresh Token 발급자가 아닙니다.** 직원 최초 인증에 대한 IdP입니다.

### 1.3 PMart 서비스의 책임 — 확정

| 서비스 | 책임 |
|---|---|
| Employee Auth Service | OIDC Client, Authorization Code/PKCE 처리, ID Token 검증, PMart 직원 JWT 발급·갱신·폐기 |
| Employee Service | 직원 인사 정보, 재직 상태, 부서·직무·근무지, 계정 프로비저닝 조정 |
| Spring Cloud Gateway | 외부 HTTP 요청 라우팅, PMart JWT 검증·접근 정책 적용(설계에 따라) |
| 업무 MSA | 자신에게 허용된 PMart JWT 검증, 업무 권한·데이터 접근 제어 |
| 직원 Flutter 앱 / 인사팀 백오피스(React SPA) | 로그인 시작, Keycloak 인증 화면으로 이동, PMart 토큰 또는 세션을 이용한 API 접근. 백오피스는 직원 등록 등 인사 업무 화면 제공 |

---

## 2. Keycloak Realm 및 Client 설계

### 2.1 Realm — 확정

- Realm: `pmart-employees`
- `master` Realm은 Keycloak 관리용으로 유지하고 일반 직원 계정은 업무 Realm에 등록
- 고객·라이더 Realm은 만들 필요 없음
- 사번 예시: `EMP000123` (인사 정보), IAM 계정 ID 예시: `gildong.hong@pmart.test` (로그인 ID)
- Keycloak `sub`(사용자 UUID)를 안정적인 외부 식별자로 저장하고 PMart 사번과 매핑
- ~~이메일 주소가 반드시 필요한 것은 아님~~ → **변경(D21)**: Keycloak은 직원 **IAM 계정**을 발급하며, IAM 계정 ID(`계정명@pmart.test`)가 Keycloak 로그인 ID이자 이메일임. 사번은 인사 정보로만 사용하고 로그인에 쓰지 않음

### 2.2 Keycloak Client — 권장 구성

| Client ID 예시 | 용도 | Grant/Flow | Client 인증 |
|---|---|---|---|
| `pmart-employee-auth` | 직원 로그인용 Employee Auth Service | Authorization Code + PKCE(S256) | Confidential / Client Secret |
| `pmart-employee-service` | Employee Service의 Keycloak Admin REST API 호출 | Client Credentials | Confidential / 서비스 계정 |
- 직원 앱이 여러 개여도 **공통 Employee Auth Service가 하나의 OIDC Client Registration**으로 로그인 처리를 중앙화할 수 있습니다.
- 앱별 별도 Client가 필요한지는 Redirect URI, 보안 정책, 인증 격리 요구에 따라 결정합니다.
- 직원 로그인 Client 권장 설정: Client Authentication ON, Standard Flow ON, PKCE S256, Implicit OFF, Direct Access Grants OFF, Authorization Services OFF, Service Accounts OFF.
- Admin API용 Client는 별도로 Service Accounts ON 및 최소 관리 권한을 부여합니다.
- **`Authorization Enabled`는 Authorization Code Flow를 켜는 옵션이 아닙니다.** Keycloak Authorization Services의 리소스·정책 기능이 필요하지 않다면 비활성화합니다.

---

## 3. 직원 최초 로그인: Authorization Code + PKCE 전체 흐름

### 3.1 참여자

- 직원 Flutter 앱 또는 인사팀 백오피스(React SPA)
- Flutter의 경우 앱이 실행하는 **시스템 브라우저**(Android Custom Tabs, iOS ASWebAuthenticationSession)
- Spring Cloud Gateway
- Employee Auth Service (**OIDC Client**)
- Keycloak (**Authorization Server / OIDC Provider**)
- PMart 직원 DB / Employee Service

### 3.2 전체 시퀀스 — 합의한 설계

```text
[1. 로그인 시작]
직원 Flutter 앱 / 인사팀 백오피스(React SPA)
    │ GET /employee-auth/oauth2/start
    ▼
Spring Cloud Gateway
    ▼
Employee Auth Service (OIDC Client)
    ├─ state, nonce 생성
    ├─ PKCE code_verifier 생성 및 code_challenge(S256) 계산
    ├─ 인증 트랜잭션 저장(만료시간·앱 연계 정보 포함)
    └─ Keycloak Authorization URL 반환
    ▲
    └──────── Gateway ──────── 직원 앱

[2. Keycloak 로그인 페이지]
직원 앱이 Authorization URL을 브라우저에서 엶
    │ Flutter: 시스템 브라우저 / 인사팀 백오피스: 현재 웹 브라우저
    ▼
Spring Cloud Gateway (/iam/**)
    ▼
Keycloak Authorization Endpoint
    ├─ 기존 Keycloak SSO 세션 확인
    ├─ 필요 시 로그인 HTML/CSS/JS 응답
    │     └─ Gateway → 브라우저에 로그인 화면 표시
    └─ 브라우저에서 입력된 사번·비밀번호(+MFA) 인증
          (로그인 폼 제출 요청도 Gateway 경유)

[3. Authorization Code 반환]
Keycloak
    │ 302 Location: https://api.pmart.test/employee-auth/callback
    │               ?code=...&state=...
    ▼
Spring Cloud Gateway (302 응답 프록시)
    ▼
Flutter 앱이 실행한 시스템 브라우저 / 인사팀 백오피스 브라우저
    │ 새 GET 요청: /employee-auth/callback?code=...&state=...
    ▼
Spring Cloud Gateway
    ▼
Employee Auth Service
    └─ state 및 인증 트랜잭션 검증, Authorization Code 수신

[4. Code → OIDC 토큰 교환]
Employee Auth Service
    │ POST Keycloak /token
    │ grant_type=authorization_code
    │ code + redirect_uri + code_verifier + Client 인증
    ▼
Keycloak
    └─ ID Token + Access Token (+ 조건에 따른 Refresh Token) 반환
    ▼
Employee Auth Service
    ├─ ID Token 검증(서명/iss/aud/exp/nonce 등)
    ├─ Keycloak sub → PMart 직원 매핑
    ├─ 재직·계정 상태 및 업무 권한 확인
    └─ PMart 자체 Access/Refresh Token 발급

[5. 직원 앱으로 결과 전달]
Flutter:
  Employee Auth Service → 브라우저에 302 App Link 응답
  → Flutter 앱 복귀 → 일회성 ticket을 Gateway 통해 교환
  → Employee Auth Service가 PMart 토큰 전달

인사팀 백오피스(React SPA):
  Employee Auth Service → Set-Cookie(PMart JWT, HttpOnly) + 302 Redirect → 백오피스
  → 이후 API 요청에는 브라우저가 쿠키를 자동 전송

[6. 업무 API 호출]
직원 앱 → Gateway → Inventory/Employee/MFC 등
    Flutter: Authorization: Bearer <PMart Employee Access Token>
    인사팀 백오피스: PMart JWT 쿠키 (Gateway가 검증 후 Authorization: Bearer 헤더로 바꿔 전달)
```

**중요:** Authorization Code는 Keycloak이 **브라우저에 302 Redirect 응답**으로 전달하며, 브라우저가 Gateway를 거쳐 Employee Auth Service Callback URL로 새 요청을 보냅니다. Code를 Token Endpoint에서 교환하는 주체는 **Employee Auth Service**입니다.

### 3.3 PKCE와 Grant Type

- Grant Type: **`authorization_code`**
- PKCE는 Grant Type이 아니라 Authorization Code Flow의 보안 확장
- `code_verifier`: Employee Auth Service가 생성·보관
- `code_challenge`: `BASE64URL(SHA256(code_verifier))`
- `code_challenge_method=S256`
- Token Endpoint에는 원래의 `code_verifier`와 Client 인증을 함께 제출
- `state`: 인증 요청/응답 결속 및 CSRF 방어
- `nonce`: OIDC ID Token과 인증 요청 결속

### 3.4 Keycloak Authorization URL 예시

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

실제 URL의 경로와 외부 호스트는 Keycloak/Gateway 배포 구성에 맞춰 일치시킵니다.

### 3.5 Keycloak Token Endpoint 호출

```http
POST /realms/pmart-employees/protocol/openid-connect/token
Authorization: Basic <base64(client_id:client_secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=AUTH_CODE
&redirect_uri=https%3A%2F%2Fapi.pmart.test%2Femployee-auth%2Fcallback
&code_verifier=ORIGINAL_VERIFIER
```

이 통신은 Employee Auth Service ↔ Keycloak 간 **백채널 서버 간 통신**이며, **Gateway를 경유하지 않고** 내부 네트워크에서 Keycloak을 직접 호출합니다.

---

## 4. ID Token과 PMart 자체 JWT의 역할

### 4.1 Keycloak ID Token

- OIDC Client인 Employee Auth Service가 **직원 신원 인증 결과**를 검증하는 데 사용
- 최소 검증: 서명·알고리즘 정책, `iss`, `aud`, `exp`, `nonce`, 로그인 트랜잭션의 일치 여부 등
- `sub`는 Keycloak 직원 UUID; PMart 직원 정보와 안전하게 매핑
- 다른 Client에 발급된 ID Token을 서명만 유효하다는 이유로 받아서는 안 됨
- **ID Token을 업무 API의 Bearer Token으로 사용하지 않음**

### 4.2 Keycloak Access/Refresh Token

- Authorization Code 교환 시 Keycloak이 Access Token을 반환함
- **PMart 업무 API 인증에는 사용하지 않음**
- Keycloak Refresh Token도 PMart의 자체 Refresh Token으로 대체하지 않음
- 실제 Spring Security OIDC Client 구현에서 저장되는 Keycloak 토큰의 수명·폐기 정책은 명시적으로 설계해야 함

### 4.3 Employee Auth Service가 발급하는 PMart 토큰

> **확정된 형식 (2026-11-11)**: 서명은 **RS256**(RSA 2048, 헤더에 `kid`)이며, 서명키는 Employee Auth Service가 부팅 시 PostgreSQL에 없으면 생성해 저장합니다. Access Token 수명은 **15분**입니다. 클레임은 `iss`, `sub`(사번), `aud`(`pmart-employee-auth`, `pmart-employee-service`, `pmart-sample-api`), `iat`, `exp`, `jti`, `user_type`(`EMPLOYEE`), `roles`(`EMPLOYEE`, `HR_ADMIN`), `identity_provider`, `external_subject`(Keycloak UUID)입니다. 개인정보와 `permissions`는 넣지 않습니다. 상세 내용은 `docs/architecture/04-tokens-and-jwt.md`를 참고하세요. 아래는 설계 논의 당시의 예시입니다.

설계 논의 당시 예시 클레임:

```json
{
  "iss": "https://employee-auth.pmart.test",
  "sub": "EMP000123",
  "user_type": "EMPLOYEE",
  "identity_provider": "keycloak",
  "external_subject": "KEYCLOAK_USER_UUID",
  "aud": ["pmart-inventory-api"],
  "roles": ["MFC_MANAGER"],
  "permissions": ["inventory:read", "inventory:update"],
  "exp": 1790000000
}
```

- 발급 전 PMart 직원의 재직 상태·승인 상태·업무 권한을 확인
- 직원의 MFC별 접근 권한처럼 데이터에 종속된 인가는 업무 서비스에서 추가 검증
- Access Token은 짧은 수명, Refresh Token은 회전·폐기·재사용 탐지 정책 고려
- Keycloak 계정 비활성화/퇴사/잠금 시 PMart 자체 세션과 Refresh Token도 폐기되도록 **연동 필요**

### 4.4 PMart의 3개 JWT 발급자

| JWT 발급자 | 예시 `iss` | 대표 사용처 |
|---|---|---|
| Customer Auth | `https://customer-auth.pmart.test` | 고객 API |
| Rider Auth | `https://rider-auth.pmart.test` | 라이더 API |
| Employee Auth | `https://employee-auth.pmart.test` | 직원/백오피스 API |

각 API는 **허용 발급자 목록, `aud`, 사용자 유형, 역할·권한**을 검사합니다. 모든 MSA가 무조건 세 발급자를 모두 신뢰해서는 안 됩니다.

---

## 5. Flutter 앱과 인사팀 백오피스의 차이

| 항목 | Flutter | React SPA (인사팀 백오피스) |
|---|---|---|
| 로그인 시작 | Gateway → Employee Auth Service | 동일 |
| 로그인 페이지 | Flutter가 연 시스템 브라우저 | 현재 웹 브라우저 |
| Keycloak 로그인 페이지 제공 | Keycloak → Gateway → 브라우저 | 동일 |
| OIDC Client | Employee Auth Service | 동일 |
| Authorization Code Callback | Gateway → Employee Auth Service | 동일 |
| 로그인 후 앱 복귀 | Android App Link / iOS Universal Link | 백오피스 URL Redirect |
| PMart 토큰 수령 | 일회성 ticket 교환 권장 | **`Set-Cookie`로 PMart JWT 수령 (확정)** — `HttpOnly`, `SameSite=Lax`, 15분 |

- Flutter 내부 **임베디드 WebView 사용은 비권장**. 시스템 브라우저 사용.
- Flutter의 App Link는 **로그인 완료 후 앱으로 돌아오는 수단**이며, Keycloak Authorization Code 자체를 Flutter 앱에 전달할 필요가 없음.
- 일회성 ticket은 OAuth 표준 Authorization Code와 **다른 PMart 내부 코드**입니다.
- ticket은 짧은 만료시간, 단일 사용, 앱의 로그인 시작 트랜잭션과의 암호학적 결속(앱 보유 verifier/nonce 등)이 필요합니다. **ticket만 아는 제3자가 토큰으로 교환할 수 있게 구현해서는 안 됩니다.**
- PMart Access/Refresh Token을 딥링크 또는 URL 쿼리 파라미터에 직접 싣지 않습니다.

---

## 6. Gateway와 Keycloak 통신

### 6.1 Gateway 경유 로그인 — 확정

Keycloak 서버는 API Gateway 뒷단에 두고, **Gateway가 Keycloak 서버로 HTTP 요청을 라우팅**합니다(Gateway에 `/iam/**` → `http://keycloak:8080` 라우트). 브라우저는 Gateway `/iam/**`를 거쳐서만 Keycloak에 접속하며, Keycloak 포트는 외부에 공개하지 않습니다.

```text
직원 앱 → Gateway → Employee Auth Service (로그인 시작)
직원 앱의 브라우저 → Gateway (/iam/**) → Keycloak (로그인 HTML/폼)
Keycloak → Gateway → 브라우저 (302 Redirect)
브라우저 → Gateway (/employee-auth/callback) → Employee Auth Service
Employee Auth Service ── 직접 서버 간 통신 ──> Keycloak /token
```

- Gateway는 Keycloak의 HTML/CSS/JS, 로그인 폼, 쿠키, 302 응답을 **HTTP 프록시**할 수 있습니다.
- Gateway가 직원 비밀번호를 검증하거나 Authorization Code를 토큰으로 교환하는 것은 아닙니다.
- `/iam/**`, 로그인 시작 및 Callback 경로는 **PMart JWT가 없는 상태에서도 접근 가능**해야 합니다.
- Keycloak Admin REST API를 직원 로그인용 공개 경로에 무차별적으로 노출하지 않습니다.
- Keycloak의 `hostname`, `http-relative-path`, 프록시 헤더 및 Gateway 경로 처리 규칙을 서로 일치시켜야 합니다.
- 실제 외부 요청은 HTTPS 권장. 내부 프록시 구간 HTTP 사용 여부는 LAN·신뢰 경계에 따라 결정.

### 6.2 별도 IAM 호스트 — 채택하지 않음

```text
직원 앱 → api.pmart.test → Spring Cloud Gateway → PMart API
직원 브라우저 → iam.pmart.test → Keycloak (또는 전용 Reverse Proxy)
```

- Keycloak을 Gateway 뒷단에 두기로 확정했으므로 이 방식은 사용하지 않음.

---

## 7. OAuth 2.0 Resource Server와 Nimbus에 대한 결론

### 7.1 개념 구분

- **Authorization Server**: OAuth 2.0 Access Token 발급 주체
- **Resource Server**: Access Token을 근거로 보호된 API 접근을 허용·거부하는 서버 역할
- **Nimbus JOSE + JWT**: Java의 JWT/JWS/JWE/JWK/JWKS 처리 라이브러리
- **Spring Security OAuth2 Resource Server**: Bearer Token 추출, JWT 검증, `Authentication`/`SecurityContext` 연동, API 인증·인가 지원

**Resource Server는 별도의 서버 제품이 아닙니다.** PMart Order/Inventory/Employee 등의 일반적인 Spring Boot MSA가 해당 역할을 수행할 수 있습니다.

### 7.2 PMart에서의 의존성

> 아래 의존성 이름은 설계 논의 당시의 예시입니다. Spring Boot 4.x에서는 일부 starter 이름이 바뀌었을 수 있으므로 Boot 4 공식 문서로 확인합니다.

```groovy
// JWT 기반 보호 API (Spring Security 인증·인가 기능 사용)
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

// JWT 생성·검증용 Spring Security JOSE API만 필요할 때
implementation 'org.springframework.security:spring-security-oauth2-jose'

// Nimbus API를 직접 사용하려는 경우
implementation 'com.nimbusds:nimbus-jose-jwt'
```

- Resource Server Starter는 내부적으로 Nimbus 관련 의존성을 포함할 수 있음.
- PMart 자체 Auth Service가 표준 OAuth 2.0 Authorization Server가 아니더라도 Spring Security Resource Server의 **JWT 인증 기능을 재사용 가능**.
- 단지 Nimbus를 쓰려고 모든 서비스에 Resource Server Starter를 추가했다면 과한 의존성일 수 있음.
- 반면 Bearer Token 인증, Spring Security 인증 객체 및 `@PreAuthorize` 연계가 목적이라면 합리적임.
- **최종 결정 전 Claude Code가 작성한 실제 `SecurityFilterChain`, `JwtDecoder`, Gradle 의존성 및 JWKS 설정을 점검**하기로 논의함.

### 7.3 JWKS 제공

- PMart의 **각 JWT 발급 Auth Service**가 RSA/EC 등 비대칭 서명을 사용한다면 공개키를 JWKS 엔드포인트로 제공 가능
- **JWKS는 내부 직접 호출**: Gateway와 샘플 업무 API는 내부 네트워크에서 Employee Auth Service의 JWKS 엔드포인트를 직접 호출하며, Gateway를 경유하지 않고 Gateway로 노출하지도 않음
- Nimbus는 JWK/JWKSet 생성 기능을 제공하지만, **HTTP JWKS 엔드포인트를 자동으로 호스팅하지는 않음**
- Spring MVC Controller 등으로 공개키만 반환
- JWT `kid`와 JWKS의 `kid`를 대응시키고 키 회전 시 기존 공개키를 유예 기간 유지
- Resource Server는 허용 발급자별 JWKS, `iss`, `aud`, 서명·만료시간을 검증
- 자체 Auth Service가 OIDC 메타데이터를 제공하지 않는다면 `issuer-uri` 자동 발견 대신 `jwk-set-uri` 또는 명시적 `JwtDecoder` 구성이 필요할 수 있음
- **Gateway와 업무 서비스에서 여러 발급자를 검증할 때는 사전 허용 목록**을 사용. 단순히 JWT의 임의 `iss`를 따라가면 안 됨.

---

## 8. 직원 입사와 Keycloak 계정 자동 발급

### 8.1 계정 발급 업무 흐름 — 권장

```text
인사팀 직원
  → PMart Backoffice(React SPA) 직원 등록
  → Spring Cloud Gateway
  → Employee Service
       ├─ HR 권한 확인
       ├─ PMart PostgreSQL 직원 정보 저장
       ├─ Keycloak Admin REST API로 직원 IAM 계정 생성
       ├─ Keycloak 사용자 UUID ↔ 사번 매핑
       ├─ (메일 운영 시) 직원 메일함 생성
       └─ 계정 발급 결과/실패 상태 기록
```

- 인사팀이 Keycloak 관리자 콘솔을 직접 사용하는 대신 **PMart 백오피스**를 사용
- Employee Service는 Keycloak의 DB 테이블을 직접 수정하지 않고 **Admin REST API** 사용
- **Employee Service가 Keycloak을 호출할 때(Client Credentials 토큰 발급, Admin REST API)는 Gateway를 경유하지 않고** 내부 네트워크에서 Keycloak을 직접 호출
- 직원 인사 DB와 Keycloak DB는 독립적인 데이터 소유권을 가짐
- 서로 다른 DB/API 간 작업은 단일 DB 트랜잭션이 아니므로 **재시도·보상·멱등성·발급 상태**를 설계

### 8.2 관리 API용 Client Credentials

```http
POST /realms/pmart-employees/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=pmart-employee-service
&client_secret=...
```

이것은 **직원 로그인용 Authorization Code Flow와 별개**입니다. Keycloak의 `realm-management`에서 필요한 사용자 관리 권한만 서비스 계정에 부여합니다.

### 8.3 신규 직원 생성 예시

```http
POST /admin/realms/pmart-employees/users
Authorization: Bearer <SERVICE_ACCOUNT_ACCESS_TOKEN>
Content-Type: application/json

{
  "username": "gildong.hong@pmart.test",
  "firstName": "길동",
  "lastName": "홍",
  "email": "gildong.hong@pmart.test",
  "enabled": true,
  "requiredActions": ["UPDATE_PASSWORD"]
}
```

- 사용자 생성과 초기 비밀번호 설정/설정 메일 발송은 별도의 절차
- 실제 메일함이 있으면 `execute-actions-email`을 통한 비밀번호 설정 링크 발송 가능
- 메일함을 사용하지 않는 경우 관리자가 초기 비밀번호를 안전하게 전달하는 절차 필요
- 퇴사·휴직·권한 변경 시 Keycloak 상태뿐 아니라 PMart 자체 Refresh Token/세션도 연계 처리

### 8.4 테스트용 인사팀 백오피스 계정 — 확정

- **테스트 용도**로, 인사팀 백오피스에 로그인할 계정(아이디·비밀번호)을 **Employee Auth Service가 부팅할 때 생성**합니다.
- 부팅이 끝나면 이 계정으로 바로 인사팀 백오피스에 로그인할 수 있어야 합니다. 로그인에 필요한 것은 모두 Employee Auth Service가 부팅 과정에서 준비합니다.
- 아이디·비밀번호는 `.env` 환경변수(예: `HR_TEST_USERNAME`, `HR_TEST_PASSWORD`)로 받고, 재부팅해도 중복 생성되지 않게 합니다.
- 테스트용 계정이므로 첫 로그인 때 비밀번호 변경을 강제하지 않습니다.

---

## 9. Keycloak 서버 설정과 최초 Realm/Client 자동 등록

### 9.1 파일별 책임

| 파일 | 책임 |
|---|---|
| `keycloak.conf` | DB, 호스트명, 포트, 프록시, 상태 확인 등 **서버 설정** |
| `pmart-employees-realm.json` | Realm, OIDC Clients, Roles 등 **IAM 초기 데이터** |
| `compose.yaml` | Keycloak 컨테이너와 Import 파일 마운트·실행 |
| `.env` / Secret | DB 비밀번호, Client Secret, 관리자 자격 증명 |

### 9.2 예시 디렉터리

```text
pmart-infrastructure/
├── compose.yaml
├── .env                 # Git 제외
└── keycloak/
    ├── keycloak.conf
    └── import/
        └── pmart-employees-realm.json
```

### 9.3 최초 Import

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:<검증된-버전>
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
    volumes:
      - ./keycloak/import:/opt/keycloak/data/import:ro
    # ports 없음: Keycloak은 API Gateway 뒷단에 두며 외부에 포트를 공개하지 않음
```

- 위는 **개발용 구조 예시**이며, PostgreSQL·Secret·호스트명·TLS 설정은 추가 필요
- JSON은 Keycloak의 `/opt/keycloak/data/import`에 마운트
- `--import-realm`은 **초기 Realm 생성**에 적합
- **이미 존재하는 Realm은 시작 시 Import에서 건너뛸 수 있으므로**, JSON 수정 후 재시작만으로 기존 Client 설정이 자동 갱신되는 것으로 가정하면 안 됨
- 이후 변경은 Admin REST API/관리 CLI 등으로 관리
- JSON의 Client Secret 치환은 사용 중인 Keycloak 버전의 지원 방식과 실제 Import 동작을 검증할 것

---

## 10. Keycloak DB와 PMart 직원 DB

| 데이터 | 소유 시스템 |
|---|---|
| Realm, Client, 인증 정책, 직원 계정, 비밀번호 해시, Keycloak 세션 | Keycloak DB |
| 사번, 이름, 부서, 직책, 입사일, 근무지, 업무 접근 범위 | Employee Service DB |
| Keycloak UUID ↔ PMart 직원 ID 매핑 | Employee Service DB |
| PMart 자체 Refresh Token 및 세션 상태 | Employee Auth Service가 정한 저장소 |

- 개발 환경에서는 기존 PostgreSQL 인스턴스에 **Keycloak 전용 DB**를 생성 가능
- 운영 수준의 물리적 분리는 교육 프로젝트의 필수 요구가 아님
- Keycloak 개발 모드의 기본값은 H2지만, 이 프로젝트에서는 **PostgreSQL을 사용**함(H2 사용하지 않음)

---

## 11. 로컬 LAN 직원 이메일 실습 구성

> **구현 결과 (2026-11-12, D22)**: Docker Mailserver 16.0.1 + Roundcube 1.7.5를 Compose로 실행. 직원은 **IAM 계정(Keycloak)으로 웹메일에 SSO 로그인**하며, Roundcube가 Keycloak 토큰으로 IMAP·SMTP에 OAuth(XOAUTH2/OAUTHBEARER) 로그인하고 Dovecot이 Keycloak userinfo로 검증. IAM 계정 ID가 메일 주소이고 직원 등록 시 메일함 생성. 상세는 `docs/infrastructure/lan-mail.md`. 아래는 설계 당시 내용.

### 11.1 도입 목적 — 실습 범위

- 외부 인터넷 메일 송수신이 아닌 **LAN 내부 직원 간 이메일 송수신**
- Keycloak 비밀번호 설정·재설정 메일 테스트
- SMTP/IMAP/웹메일, Docker 네트워킹 학습
- 상용 메일 운영 수준의 스팸 방어, 공인 메일 평판, SPF/DKIM/DMARC, 백업·고가용성 등은 **현재 실습 필수 범위에서 제외**

### 11.2 컴포넌트

| 구성 요소 | 제품/프로토콜 | 역할 |
|---|---|---|
| SMTP | Postfix / SMTP Submission | 메일 발송·전달 |
| IMAP | Dovecot / IMAP | 메일함 조회·관리 |
| Mailserver 통합 배포 | Docker Mailserver | Postfix+Dovecot 컨테이너 |
| 웹메일 | Roundcube | 브라우저용 메일 UI |
| 웹 호스팅 | Roundcube 공식 Apache 포함 이미지 | HTTP 서버 + PHP + Roundcube |
| Keycloak | SMTP Client | 직원에게 설정/재설정 메일 발송 |

**Roundcube는 SMTP/IMAP 서버가 아니라 웹메일 애플리케이션**입니다. `roundcube/roundcubemail`의 일반적인 Apache 포함 이미지에는 웹 서버와 PHP 런타임이 포함되어 별도 Apache 컨테이너가 필요하지 않습니다.

### 11.3 네트워크 구조

```text
직원 브라우저
    │ HTTP(S) :8088
    ▼
Roundcube (Apache + PHP)
    ├─ SMTP :587 / STARTTLS → Postfix
    └─ IMAP :143 / STARTTLS → Dovecot

Keycloak ── SMTP :587 / STARTTLS ──> Postfix
```

- 메일 서버는 Roundcube와 동일 PC/Compose 네트워크에 배치 가능
- 서로 다른 LAN PC에 배치할 수도 있음
- Docker 내부 통신에서는 `mailserver` 같은 **서비스 이름** 사용
- 다른 PC에서는 LAN IP 또는 내부 DNS 이름 사용
- 내부 테스트 도메인 예시: `mail.pmart.test`, `webmail.pmart.test`, `hong@pmart.test`

### 11.4 Roundcube 환경변수 예시

```yaml
services:
  roundcube:
    image: roundcube/roundcubemail:<검증된-버전>
    ports:
      - "8088:80"
    environment:
      ROUNDCUBEMAIL_DEFAULT_HOST: "tls://mailserver"
      ROUNDCUBEMAIL_DEFAULT_PORT: "143"
      ROUNDCUBEMAIL_SMTP_SERVER: "tls://mailserver"
      ROUNDCUBEMAIL_SMTP_PORT: "587"
```

이 예시는 **연결 정보만 표시한 불완전한 Compose 예시**입니다. 실제 동작에는 Mailserver 구성, 사용자 메일함, TLS 인증서, Roundcube DB(PostgreSQL)·인증 설정이 필요합니다. 사용 이미지 버전에 따라 환경변수 지원 및 SMTP 인증 설정을 검증해야 합니다.

### 11.5 TLS 인증서

- **공인 CA 인증서가 필수는 아님**. LAN 실습에서는 자체 서명 인증서 또는 내부 CA 사용 가능
- 암호화가 된다는 사실과 클라이언트가 인증서를 신뢰한다는 사실은 다름
- Roundcube가 메일 서버 인증서를 신뢰하도록 CA/인증서 설정 필요
- `mail.pmart.test` 같은 호스트명을 인증서 SAN에 포함
- 인증서 검증을 무작정 비활성화하는 방식은 피함
- 외부 포트 포워딩은 불필요. 필요한 포트만 LAN에 공개

### 11.6 직원 메일 계정 발급

- Keycloak 계정 생성 ≠ 메일함 자동 생성
- Employee Service 또는 별도 프로비저닝 절차가 **Keycloak 사용자와 메일함을 각각 생성**해야 함
- Keycloak의 메일 발송은 SMTP 사용; IMAP은 Roundcube가 메일함을 읽는 데 사용

---

## 12. 검토 후 제외하거나 우선 채택하지 않은 대안

| 대안 | 판단 | 이유 |
|---|---|---|
| SAML 2.0 + OAuth 2.0 하이브리드 | 우선 채택하지 않음 | 직원 신규 앱에 프로토콜·세션·토큰 연계 복잡성 증가 |
| 직원 로그인 전체를 SAML로 처리 | 우선 채택하지 않음 | 현재는 OIDC Authorization Code + PKCE 방향 |
| Keycloak Access Token을 모든 PMart API에서 직접 사용 | 채택하지 않음 | Employee Auth Service가 자체 JWT 발급하는 설계 유지 |
| `grant_type=password` / Direct Access Grants | 비권장 | 앱/Employee Auth가 직원 비밀번호를 직접 취급; 브라우저 기반 MFA·SSO 제약 |
| Flutter 임베디드 WebView 로그인 | 비권장 | 시스템 브라우저 기반 로그인 권장 |
| 직원 앱마다 반드시 Keycloak Client 별도 등록 | 필수 아님 | 공통 Employee Auth Service가 하나의 OIDC Client 가능 |
| 모든 업무 서비스가 Keycloak 토큰을 검증 | 채택하지 않음 | 업무 API에는 PMart Employee Auth의 자체 JWT 사용 |
| 모든 Auth Service 통합 | **명시적으로 제외** | Customer/Rider/Employee Auth 총 3개 유지 |

**Silent Authentication(`prompt=none`)**은 기존 Keycloak SSO 세션이 있을 때 화면 없이 인증을 진행하도록 요청하는 기능이며, **최초 로그인 시 비밀번호 입력을 대체하지 않습니다.**

---

## 13. 미결정·검증 필요 항목

다음은 채팅에서 방향은 논의했으나 **실제 코드/배포 검증 또는 최종 정책 결정이 필요한 항목**입니다.

1. **Gateway ↔ Keycloak 경로 설정:** Keycloak을 Gateway 뒷단에 두는 것은 확정. 남은 것은 Keycloak `hostname`/relative path/프록시 헤더 설정, Admin API·관리 콘솔 비공개 처리.
2. **Employee Auth Service 로그인 API:** 인증 시작 URL 반환 방식과 Callback 경로, 만료 시간·재시도 처리. (`state`·`nonce`·PKCE 저장소는 PostgreSQL로 확정)
3. **Flutter 로그인 완료:** 결정됨(테스트 환경) — LAN에는 HTTPS 도메인이 없어 App Link 대신 커스텀 스킴 `pmart-employee://login-callback` 사용, 일회성 ticket을 앱의 `app_verifier`에 결속(60초·단일 사용), 토큰은 기기 보안 저장소. 실제 App Link/Universal Link는 운영 환경에서 별도 검증 필요.
4. **인사팀 백오피스(React SPA) 로그인 완료:** 결정됨 — PMart JWT를 `Set-Cookie`로 전달, CORS와 CSRF 방어는 Gateway에서 적용.
5. **자체 JWT 형식:** 결정됨 — RS256, 클레임, Access Token 15분, 키 보관·회전 방식 (4.3절).
6. **Refresh Token 정책:** 결정됨 — 무작위 값(해시 저장), 로그인 후 8시간 고정, Rotation, 재사용 탐지 시 세션 폐기, 퇴사 시 Keycloak 계정 비활성화와 Refresh Token 즉시 폐기 (`docs/architecture/04-tokens-and-jwt.md`).
7. **세 Auth Service의 JWKS:** 실제 JWKS 엔드포인트 구현 여부, 공개키 로딩·캐싱·회전, Gateway/각 MSA의 허용 발급자 설정.
8. **Resource Server Starter 필요성:** Claude Code가 작성한 서비스별 `SecurityFilterChain`, `JwtDecoder`, `dependencyInsight`를 근거로 판단. 이름만 보고 제거하지 않음.
9. **Keycloak Realm JSON:** 최초 Import의 Client Secret, 역할, 관리용 서비스 계정 권한, 실제 Keycloak 버전 호환성 검증.
10. **메일 실습:** Docker Mailserver·Roundcube 버전 고정, LAN DNS, TLS 신뢰, 메일 계정 생성 자동화, Keycloak SMTP 연결.
11. **직원 권한 정책:** Keycloak IAM 역할과 PMart 업무 권한의 책임 경계, MFC 단위 접근 통제, 계정/권한 변경 이벤트 처리.
---

## 14. 훈련생 구현 권장 순서

1. **Keycloak 기동:** PostgreSQL 연결, `pmart-employees` Realm JSON 최초 Import, OIDC 로그인 Client와 Admin API용 Client 생성.
2. **직원 계정 프로비저닝:** Employee Service에서 Keycloak Admin REST API로 계정 생성, 직원 DB와 UUID 매핑.
3. **Employee Auth Service OIDC:** Authorization Code + PKCE, Callback, ID Token 검증, PMart JWT 발급.
4. **Gateway 라우팅:** 직원 로그인 API, Keycloak 로그인 페이지, Callback 경로 및 인증 예외 설정.
5. **인사팀 백오피스(React SPA) 로그인 실습:** 브라우저 Redirect 및 자체 토큰/세션 처리 검증.
6. **Flutter 로그인 실습:** 시스템 브라우저, App Link, 안전한 일회성 ticket 교환.
7. **JWT 검증:** Gateway와 필요한 업무 서비스에 발급자별 JWKS·Audience·권한 검증.
8. **Refresh/로그아웃:** PMart 자체 Refresh Token 회전, Keycloak 계정 비활성화 시 세션 폐기.
9. **LAN 메일:** Docker Mailserver(Postfix/Dovecot), Roundcube, TLS, Keycloak SMTP 및 신규 직원 메일함 연동.
10. **통합 테스트:** 고객/라이더/직원 토큰의 교차 접근 거부(고객·라이더 토큰은 테스트용 키로 서명해 만든 것을 사용하며 Customer/Rider Auth Service는 구현하지 않음), 권한 변경, 재로그인, 만료·재발급, 장애·재시도 검증.

---

## 15. 한 문장으로 정리

**PMart는 고객·라이더·직원용 Auth Service를 각각 독립적으로 운영하며, 직원 인증에만 Keycloak OIDC를 사용합니다. Employee Auth Service가 Authorization Code + PKCE로 직원 신원을 확인한 뒤 PMart 자체 Access/Refresh Token을 발급하고, Gateway와 업무 MSA는 PMart 토큰을 검증합니다. 직원 계정은 Employee Service가 Keycloak Admin REST API로 발급하며, LAN 전용 메일 서버와 Roundcube를 교육 실습에 연계합니다.**
