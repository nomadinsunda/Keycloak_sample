# 01. 시스템 개요

## 사용자 유형별 인증 분리 — 확정

| 사용자 유형 | 최초 신원 확인 | PMart JWT 발급 주체 | Keycloak 적용 |
|---|---|---|---|
| 고객 | 기존 외부 소셜 로그인 등 | **Customer Auth Service** | 없음 |
| 라이더 | 기존 외부 소셜 로그인 + 라이더 자격 확인 | **Rider Auth Service** | 없음 |
| 직원 | **Keycloak OIDC** | **Employee Auth Service** | 직원 IAM·인증 전용 |

- Auth Service는 **총 3개**이며 하나로 통합하지 않습니다. Employee Auth는 세 번째 독립 서비스입니다.
- **이 검증 저장소에서는 Employee Auth Service만 구현합니다.** Customer Auth와 Rider Auth는 PMart 전체 구조를 설명하려고 적은 것이며 구현 대상이 아닙니다.
- 각 Auth Service의 `iss`, 서명키, 토큰 수명, Refresh Token 정책은 독립적으로 운영할 수 있습니다.
- Gateway와 업무 서비스는 **API별로 신뢰할 발급자를 명시**해야 합니다.

## 구성도

```text
              ┌──── 직원 Flutter 앱 / 인사팀 백오피스 (React SPA) ────┐
              │                                                          │
              ▼                                                          ▼
     Spring Cloud Gateway ──(/iam/**, Keycloak은 Gateway 뒷단)──> Keycloak (pmart-employees)
              │                                            ▲      ▲
              ├──> Employee Auth Service ──(백채널 /token)─┘      │
              │         (OIDC Client, PMart JWT 발급)              │
              ├──> Employee Service ──(Admin REST API)────────────┘
              │         (인사 정보, 계정 프로비저닝)
              └──> 업무 MSA (Inventory, MFC, ...)  ← PMart JWT 검증
```

## Keycloak의 책임 — 확정

- 직원 계정(User), 사용자명, 비밀번호 해시, 자격 증명 관리
- 직원 로그인, MFA, 중앙 인증 세션, SSO
- OIDC ID Token 발급, 공개키(JWKS) 제공
- 직원 Realm, Client, 기본 Role/Group, 인증 정책 관리
- Employee Service 서비스 계정에 Admin API 접근 권한 부여

> **Keycloak은 PMart 자체 Access/Refresh Token 발급자가 아닙니다.** 직원 최초 인증을 위한 IdP입니다.

## PMart 서비스의 책임 — 확정

| 서비스 | 책임 |
|---|---|
| Employee Auth Service (Spring RESTful API 서버) | OIDC Client, Authorization Code/PKCE 처리, ID Token 검증, PMart 직원 JWT 발급·갱신·폐기 |
| Employee Service (Spring RESTful API 서버) | 직원 인사 정보, 재직 상태, 부서·직무·근무지, 계정 프로비저닝 조정 |
| Spring Cloud Gateway | 외부 HTTP 요청 라우팅, PMart JWT 검증·접근 정책 적용(설계에 따라) |
| 업무 MSA (이 저장소에서는 샘플 업무 API, Spring RESTful API 서버) | 허용된 PMart JWT 검증, 업무 권한·데이터 접근 제어 |
| 직원 Flutter 앱 / 인사팀 백오피스(React SPA) | 로그인 시작, Keycloak 인증 화면 이동, PMart 토큰 또는 세션으로 API 접근. 백오피스는 직원 등록 등 인사 업무 화면 제공 |

## 데이터 소유권

| 데이터 | 소유 시스템 |
|---|---|
| Realm, Client, 인증 정책, 직원 계정, 비밀번호 해시, Keycloak 세션 | Keycloak DB |
| 사번, 이름, 부서, 직책, 입사일, 근무지, 업무 접근 범위 | Employee Service DB |
| Keycloak UUID ↔ PMart 직원 ID 매핑 | Employee Service DB |
| PMart 자체 Refresh Token, 세션 상태 | Employee Auth Service가 정한 저장소 |

- 두 DB는 독립적인 소유권을 가지며, 서비스 간 접근은 API로만 합니다.
- 개발 환경에서는 기존 PostgreSQL 인스턴스에 Keycloak 전용 DB를 만들어도 됩니다. 물리적 분리는 교육 프로젝트의 필수 요구가 아닙니다.
