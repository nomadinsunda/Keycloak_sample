# PMart 직원 IAM 문서

원본 설계 기록 [`PMart_Employee_IAM_Authentication_Architecture.md`](../PMart_Employee_IAM_Authentication_Architecture.md)를 주제별로 나눈 문서입니다.

## 프로젝트 목적

**이 저장소는 PMart 프로젝트에 Keycloak을 적용할지 검증하기 위한 테스트(샘플) 프로젝트입니다.** PMart 본 프로젝트의 코드베이스가 아닙니다.

- 설계 문서의 Keycloak 기반 직원 인증 구조가 실제로 동작하는지 작은 규모로 구현해 확인합니다.
- 검증 결과를 근거로 PMart 본 프로젝트에 Keycloak을 적용할지, 어떤 방식으로 적용할지 판단합니다.
- 코드는 **검증에 필요한 최소 범위**로 작성합니다. 고객·라이더 인증과 업무 기능은 구현하지 않으며, 업무 MSA는 JWT 검증을 확인할 수 있을 정도의 샘플 API로 대신합니다.
- 검증 중 설계와 다른 결과(동작하지 않음, 버전 제약, 설정 충돌 등)가 나오면 문서에 기록합니다. 그 기록이 이 프로젝트의 핵심 결과물입니다.

### 구현하지 않는 것: Customer Auth Service, Rider Auth Service

- **고객용 Customer Auth Service와 라이더용 Rider Auth Service는 이 저장소에서 구현하지 않습니다.** 두 서비스는 PMart 본 프로젝트에 이미 있거나 따로 개발되며, Keycloak과 관계가 없습니다.
- 이 저장소에서 구현하는 Auth Service는 **Employee Auth Service 하나뿐**입니다.
- 문서에 고객·라이더 Auth가 나오는 이유는 PMart 전체 구조(발급자 3개)와 서비스 분리 원칙을 설명하기 위해서입니다. 구현 대상이라는 뜻이 아닙니다.
- 다른 발급자의 토큰이 거부되는지 확인하는 테스트(교차 접근 거부)에는 실제 서비스 대신 **테스트용 키로 서명한 고객·라이더 토큰**을 사용합니다.

## 기술 버전과 실행 환경 — 확정

- **Spring Boot 4.x**: 모든 Spring Boot 서비스(Employee Auth Service, Employee Service, Gateway, 샘플 업무 API)는 Spring Boot 4.x로 만듭니다. Spring Security, Spring Cloud 등 관련 의존성도 Boot 4와 호환되는 버전을 씁니다. 3.x 예제나 문서의 의존성 예시는 Boot 4 기준 공식 문서로 확인한 뒤 사용합니다.
- **인사팀 백오피스는 React SPA**: 인사팀이 사용하는 앱(PMart 백오피스)은 React SPA로 만듭니다. 빌드 도구는 **Vite**, 스타일은 **Tailwind CSS + daisyUI**이며 둘 다 작업 시점의 최신 안정 버전을 확인해 고정합니다. 이 저장소의 웹 클라이언트는 인사팀 백오피스 하나뿐이며, 별도의 "React 직원 웹"은 만들지 않습니다. 로그인이 끝나면 Employee Auth Service가 **PMart JWT를 `Set-Cookie`로 전달**합니다. 백오피스와 Gateway의 Origin이 다르므로 **CORS는 Gateway에서 적용**하고, 쿠키 인증을 쓰므로 **CSRF 방어도 Gateway에서 적용**합니다.
- **Keycloak은 API Gateway 뒷단**: **Gateway가 Keycloak 서버로도 HTTP 요청을 라우팅**하며(`/iam/**` → Keycloak), 브라우저는 항상 이 경로를 거쳐 Keycloak에 접속합니다. 별도 IAM 호스트로 직접 노출하지 않으며, Keycloak 포트는 외부에 공개하지 않습니다. 단, **Employee Auth Service·Employee Service가 Keycloak을 호출할 때는 Gateway를 경유하지 않고** 내부 네트워크에서 직접 호출합니다. Employee Auth Service의 **JWKS도 내부 직접 호출**이며 Gateway로 노출하지 않습니다.
- **Employee Auth Service, Employee Service, 샘플 업무 API는 Spring RESTful API 서버**: Spring Boot 4 기반으로, Spring Web MVC `@RestController`와 JSON 요청·응답을 사용합니다. 화면(HTML)은 만들지 않습니다.
- **테스트용 인사팀 계정**: 테스트 용도로, 인사팀 백오피스의 계정과 비밀번호는 **Employee Auth Service가 부팅할 때 생성**합니다. 부팅 후 바로 이 계정으로 로그인할 수 있습니다.
- **API Gateway는 Spring Boot 4 + Netty**: Gateway도 Spring Boot 4.x로 만듭니다. Spring Cloud Gateway는 WebFlux 기반(reactive)으로 만들고 내장 서버로 Netty를 사용합니다. 서블릿(Tomcat) 기반의 Gateway Server Web MVC 방식은 쓰지 않으며, Gateway 보안 설정도 reactive API로 작성합니다.
- **PostgreSQL**: 데이터베이스가 필요한 경우 PostgreSQL을 사용합니다(Keycloak, Employee Service, Employee Auth Service, Roundcube 등). H2, MySQL 등 다른 DB는 쓰지 않으며, 구성 요소별로 DB와 전용 계정을 나눕니다. 로그인 트랜잭션(`state`·`nonce`·PKCE)도 PostgreSQL에 저장하며 Redis는 쓰지 않습니다.
- **Docker Desktop + Docker Compose**: **Flutter 앱을 제외한 모든 아티팩트**(Keycloak, PostgreSQL, Gateway, Employee Auth Service, Employee Service, 샘플 업무 API, 인사팀 백오피스(React SPA), Docker Mailserver, Roundcube)는 Docker Desktop에서 Docker Compose로 실행합니다.
- **Flutter 앱**은 Compose 밖(에뮬레이터 또는 실기기)에서 실행하며, 호스트에 공개된 Gateway 포트로 접근합니다.

## 한 문장 요약

PMart는 고객·라이더·직원용 Auth Service를 각각 독립적으로 운영하며, 직원 인증에만 Keycloak OIDC를 사용합니다. Employee Auth Service가 Authorization Code + PKCE로 직원 신원을 확인한 뒤 PMart 자체 Access/Refresh Token을 발급하고, Gateway와 업무 MSA는 PMart 토큰을 검증합니다. 직원 계정은 Employee Service가 Keycloak Admin REST API로 발급하며, LAN 전용 메일 서버와 Roundcube를 교육 실습에 연계합니다.

## 읽는 순서

1. [시스템 개요](architecture/01-system-overview.md) — 누가 무엇을 책임지는가
2. [Keycloak Realm·Client](architecture/02-keycloak-realm-and-clients.md)
3. [직원 로그인 흐름](architecture/03-employee-login-flow.md) — 가장 중요한 문서
4. [토큰과 JWT](architecture/04-tokens-and-jwt.md)
5. [Gateway 라우팅](architecture/05-gateway-routing.md)
6. [직원 계정 프로비저닝](architecture/06-employee-provisioning.md)
7. 인프라: [Keycloak 설정](infrastructure/keycloak-setup.md), [LAN 메일](infrastructure/lan-mail.md)
8. [결정 기록](decisions.md), [미결정 항목](open-questions.md)(상세 검토: [issues/](issues/)), [구현 로드맵](roadmap.md), [용어집](glossary.md)

## 상태 표기

| 표기 | 의미 |
|---|---|
| **확정** | 합의된 결정. 변경하려면 논의 필요 |
| **권장** | 권장 구현. 근거가 있으면 바꿀 수 있음 |
| **논의된 배포안** | 하나의 선택지로 논의됨. 대안도 가능 |
| **미결정** | 구현·검증 후 결정 필요 ([open-questions.md](open-questions.md)) |

> 문서의 도메인(`*.pmart.test`), IP, 포트, Client ID는 모두 예시입니다.
