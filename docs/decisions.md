# 설계 결정 기록

결정이 바뀌면 이 문서에 날짜와 이유를 남깁니다.

## 채택한 결정 (2026-11-11)

| # | 결정 | 상태 |
|---|---|---|
| D1 | Customer / Rider / Employee Auth Service **3개 분리 운영** | 확정 |
| D2 | Keycloak은 **직원 IAM·최초 인증 전용** | 확정 |
| D3 | **Employee Auth Service**가 Keycloak의 Confidential OIDC Client | 확정 |
| D4 | 직원 로그인은 **Authorization Code + PKCE(S256)**, `scope=openid` | 확정 |
| D5 | Employee Auth가 ID Token 검증 후 **PMart 자체 Access/Refresh Token 발급** | 확정 |
| D6 | 직원 앱 외부 API는 **Spring Cloud Gateway** 경유 | 확정 |
| D7 | **Keycloak은 API Gateway 뒷단**에 두고, 로그인 페이지를 Gateway `/iam/**`로 프록시. Keycloak 포트는 외부 비공개 | 확정 |
| D8 | 계정 발급은 백오피스 → Employee Service → **Keycloak Admin REST API** | 권장 |
| D9 | 직원 인사 DB와 Keycloak DB **책임 분리** | 확정 |
| D10 | LAN 전용 SMTP/IMAP + Roundcube 실습 환경, 컨테이너 분리 | 논의된 방향 |
| D11 | Flutter는 시스템 브라우저 + App Link + 일회성 ticket 교환 | 권장 |
| D12 | **테스트용** 인사팀 백오피스 계정(아이디·비밀번호)은 **Employee Auth Service가 부팅 시 생성**, 부팅 후 바로 로그인 가능 | 확정 |
| D13 | 로그인 트랜잭션(`state`·`nonce`·PKCE `code_verifier`) 저장소는 **PostgreSQL**(Employee Auth DB). 사용 즉시 삭제, 만료분 주기 정리. Redis 사용 안 함 | 확정 |
| D14 | PMart JWT: **RS256**(RSA 2048, `kid`), 서명키는 Employee Auth가 PostgreSQL에 생성·보관, Access Token 15분, 클레임 `iss`·`sub`(사번)·`aud`·`iat`·`exp`·`jti`·`user_type`·`roles`·`identity_provider`·`external_subject`, 테스트 역할 `EMPLOYEE`·`HR_ADMIN` | 확정 |
| D15 | 인사팀 백오피스 로그인 완료 시 Employee Auth가 **PMart JWT를 `Set-Cookie`로 전달**(`HttpOnly`, `SameSite=Lax`, 15분). Gateway는 쿠키 JWT를 검증해 하위 서비스에 `Authorization: Bearer`로 전달 | 확정 |
| D16 | **CORS는 Gateway에서만 적용**. 허용 Origin은 백오피스 주소만 명시, `allowCredentials=true`, reactive Spring Security `cors()`로 preflight 허용 | 확정 |
| D17 | **CSRF 방어는 Gateway에서** Double Submit Cookie 방식으로 적용. `GET /csrf`로 토큰 발급, `X-XSRF-TOKEN` 헤더 검사. 쿠키 인증 상태 변경 요청만 대상, Bearer 헤더 요청·`/iam/**`·Callback 제외 | 확정 |
| D18 | 구현 시 결정(2026-11-11): 부트스트랩 Client `pmart-employee-auth-bootstrap`, Employee Service 내부 API + 내부 API 키, 역할은 Employee Service DB에 저장, PMart JWT `aud`에 `pmart-employee-auth` 추가. 상세는 [verification-log.md](verification-log.md) | 확정 |

## 채택하지 않은 대안

| 대안 | 판단 | 이유 |
|---|---|---|
| 모든 Auth Service 통합 | **명시적 제외** | Customer/Rider/Employee Auth 3개 유지 |
| 별도 IAM 호스트로 Keycloak 직접 노출 | 채택 안 함 | Keycloak은 API Gateway 뒷단에 두기로 확정 |
| SAML 2.0 + OAuth 2.0 하이브리드 | 우선 채택 안 함 | 신규 직원 앱에 프로토콜·세션·토큰 연계 복잡성 증가 |
| 직원 로그인 전체를 SAML로 처리 | 우선 채택 안 함 | OIDC Authorization Code + PKCE 방향 |
| Keycloak Access Token을 모든 PMart API에서 직접 사용 | 채택 안 함 | Employee Auth 자체 JWT 발급 설계 유지 |
| 모든 업무 서비스가 Keycloak 토큰 검증 | 채택 안 함 | 업무 API는 PMart Employee Auth JWT 사용 |
| `grant_type=password` / Direct Access Grants | 비권장 | 앱·서비스가 비밀번호를 직접 취급, 브라우저 기반 MFA·SSO 제약 |
| Flutter 임베디드 WebView 로그인 | 비권장 | 시스템 브라우저 기반 로그인 권장 |
| 직원 앱마다 Keycloak Client 별도 등록 | 필수 아님 | 공통 Employee Auth Service가 하나의 Client로 처리 가능 |

## 자주 하는 오해

- **`prompt=none`(Silent Authentication)** 은 기존 SSO 세션이 있을 때만 동작하며, 최초 로그인의 비밀번호 입력을 대체하지 않습니다.
- **`Authorization Enabled`** 는 Authorization Code Flow 스위치가 아니라 Keycloak Authorization Services 기능입니다.
- **PKCE는 Grant Type이 아닙니다.** Grant Type은 `authorization_code`입니다.
- **Resource Server는 서버 제품이 아니라 역할**입니다.
