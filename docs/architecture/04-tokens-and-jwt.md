# 04. 토큰과 JWT

## 토큰별 용도 요약

| 토큰 | 발급자 | 사용처 | 업무 API에 사용 |
|---|---|---|---|
| Keycloak ID Token | Keycloak | Employee Auth Service의 신원 확인 | **아니오** |
| Keycloak Access Token | Keycloak | (교환 시 함께 반환됨) | **아니오** |
| Keycloak Refresh Token | Keycloak | (조건에 따라 반환됨) | **아니오** |
| PMart Employee Access Token | Employee Auth Service | Gateway·업무 MSA 인증 | **예** |
| PMart Employee Refresh Token | Employee Auth Service | PMart Access Token 재발급 | 재발급 엔드포인트만 |

## Keycloak ID Token

- Employee Auth Service가 **직원 신원 인증 결과**를 검증하는 데 사용합니다.
- 최소 검증 항목: 서명 및 허용 알고리즘, `iss`, `aud`(= `pmart-employee-auth`), `exp`, `nonce`, 로그인 트랜잭션 일치 여부
- `sub`는 Keycloak 직원 UUID이며 PMart 직원 정보와 매핑합니다.
- 다른 Client에 발급된 ID Token을 **서명이 유효하다는 이유만으로 받지 않습니다.**
- ID Token을 업무 API Bearer Token으로 사용하지 않습니다.

## Keycloak Access/Refresh Token

- Code 교환 시 반환되지만 **PMart 업무 API 인증에 사용하지 않습니다.**
- Keycloak Refresh Token을 PMart Refresh Token 대신 사용하지 않습니다.
- Spring Security OIDC Client 구현에서 Keycloak 토큰이 저장된다면 그 수명·폐기 정책을 명시적으로 설계합니다.

## PMart Employee Access Token 형식 — 확정 (D14)

### 서명

| 항목 | 값 |
|---|---|
| 서명 알고리즘 | **RS256** (RSA 2048bit) |
| 헤더 | `{"alg": "RS256", "typ": "JWT", "kid": "<키 ID>"}` |
| 서명키 보관 | Employee Auth Service가 부팅 시 PostgreSQL(`employee_auth` DB)에 활성 키가 없으면 RSA 키쌍을 생성해 저장. 재시작해도 같은 키를 사용 |
| 공개키 제공 | Employee Auth Service의 `/.well-known/jwks.json` (내부 직접 호출, Gateway로 노출 안 함) |
| 키 회전 | 새 키로 서명을 시작해도 이전 공개키는 남은 토큰이 만료될 때까지 JWKS에 함께 둠 |

- RS256을 고른 이유: Keycloak ID Token 기본 알고리즘과 같고, Spring Security `NimbusJwtDecoder`·`NimbusReactiveJwtDecoder`의 기본값이라 설정이 가장 단순합니다.
- 개인키를 DB에 저장하는 방식은 테스트 프로젝트용입니다. 운영 환경이라면 KMS·Vault 같은 별도 키 저장소가 필요합니다.

### 클레임

```json
{
  "iss": "https://employee-auth.pmart.test",
  "sub": "EMP000123",
  "aud": ["pmart-employee-auth", "pmart-employee-service", "pmart-sample-api"],
  "iat": 1790000000,
  "exp": 1790000900,
  "jti": "6f1c2b3e-...-uuid",
  "user_type": "EMPLOYEE",
  "roles": ["EMPLOYEE", "HR_ADMIN"],
  "identity_provider": "keycloak",
  "external_subject": "KEYCLOAK_USER_UUID"
}
```

| 클레임 | 값 | 설명 |
|---|---|---|
| `iss` | `https://employee-auth.pmart.test` (환경변수로 설정) | 발급자 식별자. 접속 주소가 아니라 이름이므로 실제로 열리지 않아도 됨 |
| `sub` | 사번 | PMart 직원 식별자 |
| `aud` | 토큰을 받을 API 목록 | 테스트 범위: `pmart-employee-auth`, `pmart-employee-service`, `pmart-sample-api` |
| `iat` / `exp` | 발급·만료 시각 | **Access Token 수명 15분** |
| `jti` | UUID | 토큰 고유 ID (추적·폐기용) |
| `user_type` | `EMPLOYEE` 고정 | 고객·라이더 토큰과 구분 |
| `roles` | 역할 목록 | 테스트 범위: `EMPLOYEE`(모든 직원), `HR_ADMIN`(인사팀) |
| `identity_provider` | `keycloak` | 최초 인증 수단 |
| `external_subject` | Keycloak 사용자 UUID | Keycloak 계정과 연결 |

- 이름·이메일 같은 개인정보는 넣지 않습니다. 필요하면 API로 조회합니다.
- `permissions`처럼 세분화된 권한은 넣지 않습니다. 테스트 범위에서는 `roles`로 충분합니다.
- Refresh Token 형식과 정책은 [미결정 #6](../open-questions.md)에서 따로 정합니다.

### 검증 규칙

| 검증하는 쪽 | 검사 항목 |
|---|---|
| Gateway | 서명(JWKS), `iss` 정확히 일치, `exp`, `user_type == EMPLOYEE` |
| Employee Service | 위 항목 + `aud`에 `pmart-employee-service` 포함, 직원 등록 API는 `HR_ADMIN` 역할 필요 |
| 샘플 업무 API | 위 항목 + `aud`에 `pmart-sample-api` 포함, 역할 검사 |

- 시계 오차 허용은 60초로 합니다.
- Spring Security에서는 `roles`를 `ROLE_` 접두사가 붙은 권한으로 바꿔 `hasRole("HR_ADMIN")`으로 검사합니다.

- 발급 전 재직 상태·승인 상태·업무 권한을 확인합니다.
- MFC별 접근처럼 **데이터에 종속된 인가는 업무 서비스가 추가로 검증**합니다.
- Access Token은 짧은 수명, Refresh Token은 회전(Rotation)·폐기·재사용 탐지를 고려합니다.
- Keycloak 계정 비활성화·퇴사·잠금 시 PMart 세션과 Refresh Token도 폐기되도록 **연동이 필요**합니다.

## PMart의 3개 JWT 발급자

| 발급자 | 예시 `iss` | 대표 사용처 |
|---|---|---|
| Customer Auth | `https://customer-auth.pmart.test` | 고객 API |
| Rider Auth | `https://rider-auth.pmart.test` | 라이더 API |
| Employee Auth | `https://employee-auth.pmart.test` | 직원/백오피스 API |

각 API는 **허용 발급자 목록, `aud`, 사용자 유형, 역할·권한**을 검사합니다. 모든 MSA가 세 발급자를 무조건 신뢰해서는 안 됩니다.

## Resource Server와 Nimbus

### 개념

- **Authorization Server**: OAuth 2.0 Access Token 발급 주체
- **Resource Server**: Access Token을 근거로 API 접근을 허용·거부하는 **역할**. 별도 서버 제품이 아니며, 일반 Spring Boot MSA가 이 역할을 합니다.
- **Nimbus JOSE + JWT**: Java JWT/JWS/JWE/JWK/JWKS 라이브러리
- **Spring Security OAuth2 Resource Server**: Bearer Token 추출, JWT 검증, `Authentication`/`SecurityContext` 연동

### 의존성 선택

> 이 프로젝트는 **Spring Boot 4.x**를 사용합니다. 아래 이름은 설계 논의 당시의 예시이며, Boot 4에서 바뀐 starter 이름이 있을 수 있으므로 Boot 4 공식 문서로 확인합니다.

```groovy
// JWT 기반 보호 API (Spring Security 인증·인가 사용)
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

// Spring Security JOSE API만 필요할 때 (예: Auth Service의 JWT 서명)
implementation 'org.springframework.security:spring-security-oauth2-jose'

// Nimbus API를 직접 사용할 때
implementation 'com.nimbusds:nimbus-jose-jwt'
```

- PMart Auth Service가 표준 Authorization Server가 아니어도 Resource Server의 **JWT 인증 기능은 재사용할 수 있습니다.**
- Bearer 인증, `@PreAuthorize` 연계가 목적이면 Starter 사용이 합리적입니다. 단지 Nimbus를 쓰려고 모든 서비스에 추가했다면 과할 수 있습니다.
- 최종 판단은 실제 `SecurityFilterChain`, `JwtDecoder`, `dependencyInsight`를 점검한 뒤 합니다 ([미결정 #8](../open-questions.md)).

## JWKS

- 각 JWT 발급 Auth Service가 비대칭 서명(RSA/EC)을 쓰면 공개키를 JWKS 엔드포인트로 제공합니다.
- **JWKS는 내부 직접 호출입니다.** Gateway와 샘플 업무 API는 Compose 내부 네트워크에서 `http://employee-auth:8080`의 JWKS 엔드포인트를 직접 호출합니다. Gateway를 경유하지 않으며, JWKS 엔드포인트를 Gateway로 노출하지 않습니다.
- Nimbus는 JWK/JWKSet 생성 기능을 주지만 **HTTP 엔드포인트를 자동 호스팅하지 않습니다.** Controller로 공개키만 반환합니다.
- JWT 헤더 `kid`와 JWKS `kid`를 대응시키고, 키 회전 시 이전 공개키를 유예 기간 동안 유지합니다.
- Auth Service가 OIDC Discovery 메타데이터를 제공하지 않으면 `issuer-uri` 대신 `jwk-set-uri` 또는 명시적 `JwtDecoder` 구성이 필요합니다.
- 여러 발급자를 검증할 때는 **사전 허용 목록**을 사용합니다. 토큰의 임의 `iss`를 따라 JWKS를 조회하지 않습니다.
