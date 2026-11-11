# CLAUDE.md

이 파일은 Claude Code가 이 저장소에서 작업할 때 따라야 할 기준을 정리합니다.

## 프로젝트 목적

**이 저장소는 PMart 프로젝트에 Keycloak을 적용할지 검증하기 위한 테스트(샘플) 프로젝트입니다.** PMart 본 프로젝트의 코드베이스가 아닙니다.

- 설계 문서의 Keycloak 기반 직원 인증 구조가 실제로 동작하는지 작은 규모로 구현해 확인합니다.
- 검증 결과를 근거로 PMart 본 프로젝트에 Keycloak을 적용할지, 어떤 방식으로 적용할지 판단합니다.
- 따라서 이곳의 코드는 **검증에 필요한 최소 범위**로 작성합니다. PMart 전체 기능(고객·라이더 인증, 주문 등 업무 기능)을 구현하지 않으며, 업무 MSA는 JWT 검증을 확인할 수 있을 정도의 샘플 API로 대신합니다.
- 검증 중 설계와 다른 결과(동작하지 않음, 버전 제약, 설정 충돌 등)가 나오면 숨기지 말고 문서에 기록합니다. 그 기록이 이 프로젝트의 핵심 산출물입니다.

### 구현하지 않는 것: Customer Auth Service, Rider Auth Service

- **고객용 Customer Auth Service와 라이더용 Rider Auth Service는 이 저장소에서 구현하지 않습니다.** 두 서비스는 PMart 본 프로젝트에 이미 있거나 따로 개발되며, Keycloak과 관계가 없습니다.
- 이 저장소에서 구현하는 Auth Service는 **Employee Auth Service 하나뿐**입니다.
- 설계 문서에 고객·라이더 Auth가 나오는 이유는 PMart 전체 구조(발급자 3개)와 서비스 분리 원칙을 설명하기 위해서입니다. 구현 대상이라는 뜻이 아닙니다.
- 다른 발급자의 토큰이 거부되는지 확인하는 테스트(교차 접근 거부)에는 실제 서비스 대신 **테스트용 키로 서명한 고객·라이더 토큰**을 사용합니다.

## 프로젝트 개요

- **PMart**: 국비 훈련생 교육용 MSA 커머스 프로젝트 (이 저장소는 그 Keycloak 적용 검증용)
- **검증 범위**: PMart **직원(Employee) IAM 및 인증** — Keycloak 기반 직원 로그인, Employee Auth Service의 자체 JWT 발급, Keycloak 계정 프로비저닝, LAN 메일 실습 환경
- **현재 상태**: 0~8단계 구현·검증 완료 (Keycloak, Gateway, Employee Auth/Employee Service, 샘플 API, 인사팀 백오피스). 남은 단계는 Refresh Token, Flutter, LAN 메일. 상세는 [`docs/verification-log.md`](docs/verification-log.md) (2026-11-11 기준)
- **원본 설계 기록**: [`PMart_Employee_IAM_Authentication_Architecture.md`](PMart_Employee_IAM_Authentication_Architecture.md) — 주제별 정리본은 [`docs/`](docs/README.md)에 있습니다. 두 곳이 충돌하면 원본을 우선하고, 정리본을 고칩니다.

## 기술 스택 (설계 기준)

- Backend: **Spring Boot 4.x** MSA, Spring Security (OAuth2 Client / Resource Server), Spring Cloud Gateway, Gradle
- IAM: Keycloak (Realm `pmart-employees`)
- Database: **PostgreSQL** (데이터베이스가 필요한 모든 구성 요소)
- Client: Flutter 직원 앱, **인사팀 백오피스 React SPA** (Vite + Tailwind CSS + daisyUI)
- 실습 인프라: **Docker Desktop + Docker Compose**, Docker Mailserver(Postfix + Dovecot), Roundcube

## Spring Boot 버전 — 확정

- 모든 Spring Boot 서비스(Employee Auth Service, Employee Service, Gateway, 샘플 업무 API)는 **Spring Boot 4.x**를 사용합니다. 3.x로 만들지 않습니다.
- Spring Security, Spring Cloud(Gateway 포함) 등 관련 의존성은 **Spring Boot 4와 호환되는 버전**을 사용합니다. Spring Cloud는 Boot 4를 지원하는 릴리스 트레인을 BOM으로 가져옵니다.
- Spring Boot 4에서는 일부 starter 이름과 설정 방식이 바뀌었습니다. 인터넷의 Spring Boot 3.x 예제나 설계 문서의 의존성 예시를 그대로 복사하지 말고, **Spring Boot 4 / Spring Security 7 공식 문서로 확인**한 뒤 작성합니다.
- Java 버전은 Spring Boot 4가 지원하는 LTS 버전 중 하나로 정하고, 모든 서비스와 Dockerfile 기본 이미지에서 같은 버전을 씁니다.

## 인사팀 백오피스 앱: React SPA — 확정

- **인사팀이 사용하는 앱(PMart 백오피스)은 React SPA로 만듭니다.** 이 저장소의 웹 클라이언트는 인사팀 백오피스 하나뿐이며, 별도의 "React 직원 웹"은 만들지 않습니다.
  - 인사팀 직원도 직원이므로 Keycloak → Employee Auth Service 흐름으로 로그인합니다.
  - 직원 등록 화면은 Gateway를 거쳐 Employee Service를 호출하고, Employee Service가 Keycloak Admin REST API로 계정을 만듭니다. SPA가 Keycloak Admin API를 직접 호출하지 않습니다.
- **SPA**: 화면 전환은 클라이언트 라우팅으로 처리합니다. Next.js 같은 SSR 프레임워크나 서버 템플릿 방식을 쓰지 않습니다.
- **빌드 도구: Vite**. Create React App은 쓰지 않습니다.
- **스타일: Tailwind CSS + daisyUI**, 둘 다 **작업 시점의 최신 안정 버전**을 사용합니다.
  - 설치 전에 npm 레지스트리나 공식 문서로 최신 버전을 확인하고, `package.json`과 lock 파일에 그 버전을 고정합니다.
  - Tailwind CSS와 daisyUI는 메이저 버전마다 설치·설정 방식(Vite 플러그인, CSS 설정 방식, 설정 파일 유무)이 크게 다릅니다. 예전 버전 예제를 그대로 쓰지 말고, 설치한 버전의 공식 문서를 따릅니다.
  - 별도 UI 컴포넌트 라이브러리(MUI, Ant Design 등)는 추가하지 않습니다.
- **Docker Compose 실행**: Vite로 빌드한 정적 파일을 웹 서버 컨테이너(예: Nginx)로 제공합니다. SPA이므로 알 수 없는 경로는 `index.html`로 돌려주는 fallback 설정이 필요합니다.
- **로그인 완료 처리: PMart JWT를 `Set-Cookie`로 전달합니다** (확정, D15).
  - Callback 처리가 끝나면 Employee Auth Service가 PMart Access Token(JWT)을 `Set-Cookie`로 내려주고 백오피스로 302 Redirect합니다.
  - 쿠키 속성: `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age`는 토큰 수명(15분)과 같게. `Secure`는 HTTPS로 접속할 때 켭니다(브라우저는 `localhost`를 빼면 HTTP에서 `Secure` 쿠키를 저장하지 않음).
  - SPA는 토큰을 직접 읽거나 `localStorage`에 저장하지 않습니다. API 요청 시 브라우저가 쿠키를 자동으로 보냅니다. 로그인 사용자 정보가 필요하면 API(예: `/employee-auth/me`)로 조회합니다.
  - Gateway는 쿠키의 JWT를 검증하고, 하위 서비스로 넘길 때 `Authorization: Bearer` 헤더로 바꿔 전달합니다. 그래서 Employee Service와 샘플 업무 API는 헤더만 읽으면 됩니다.
- **CSRF 방어: Gateway에서 적용합니다** (확정, D17).
  - Double Submit Cookie 방식입니다. SPA가 `GET /csrf`로 토큰을 받아 메모리에 보관하고, `POST`·`PUT`·`PATCH`·`DELETE` 요청에 `X-XSRF-TOKEN` 헤더로 보냅니다. Gateway는 헤더 값과 `XSRF-TOKEN` 쿠키 값을 비교해 다르면 403으로 거부합니다.
  - 검사 대상은 PMart JWT **쿠키로 인증하는** 상태 변경 요청입니다(직원 등록, 로그아웃 등).
  - 검사 제외: `Authorization: Bearer` 헤더 요청(Flutter 앱), `/iam/**`(Keycloak 자체 CSRF 방어가 있어 Gateway가 검사하면 로그인이 깨짐), `/employee-auth/callback`.
  - 하위 서비스(Employee Auth Service, Employee Service, 샘플 업무 API)는 Bearer 헤더만 받으므로 CSRF를 끕니다.
  - GET 요청으로 데이터를 바꾸는 API는 만들지 않습니다.
- **CORS: Gateway에서 적용합니다** (확정, D16).
  - 백오피스는 자기 Nginx 컨테이너 주소에서 제공되고 API는 Gateway로 호출하므로 Origin이 다릅니다.
  - CORS는 **Gateway에서만** 설정합니다. 하위 서비스(Employee Auth Service, Employee Service, 샘플 업무 API)는 CORS 헤더를 붙이지 않습니다.
  - 허용 Origin은 백오피스 주소만 환경변수(예: `BACKOFFICE_ORIGIN`)로 명시하고 `*`를 쓰지 않습니다. 쿠키를 보내야 하므로 `allowCredentials=true`입니다.
  - reactive Spring Security의 `cors()` 설정으로 적용해 preflight(`OPTIONS`)가 인증 없이 통과하게 합니다.
  - SPA는 `credentials: 'include'`(axios는 `withCredentials: true`)로 요청합니다.
  - `SameSite=Lax` 쿠키이므로 백오피스와 Gateway는 같은 호스트(포트만 다름)에 둡니다.
  - 상세 내용은 [`docs/architecture/05-gateway-routing.md`](docs/architecture/05-gateway-routing.md)에 있습니다.
  - 로그아웃은 같은 이름의 쿠키를 `Max-Age=0`으로 내려 삭제합니다.
  - 상세 내용은 [`docs/architecture/03-employee-login-flow.md`](docs/architecture/03-employee-login-flow.md)에 있습니다.

## Employee Auth Service, Employee Service, 샘플 업무 API: Spring RESTful API 서버 — 확정

- Employee Auth Service, Employee Service, 샘플 업무 API는 **Spring Boot 4 기반 RESTful API 서버**로 만듭니다.
- Spring Web MVC의 `@RestController`로 API를 만들고, 요청·응답은 JSON을 사용합니다.
- 화면(HTML)은 만들지 않습니다. 로그인 화면은 Keycloak이, 업무 화면은 Flutter 앱과 인사팀 백오피스가 담당합니다.

## Keycloak 위치: API Gateway 뒷단 — 확정

- **Keycloak 서버는 API Gateway 뒷단에 둡니다.**
- **Gateway는 Keycloak 서버로도 HTTP 요청을 라우팅합니다.** Gateway에 Keycloak용 라우트를 두고, `/iam/**`로 들어온 요청을 Keycloak(`http://keycloak:8080`)으로 전달합니다. 업무 서비스로 가는 라우트와 같은 방식입니다.
- 브라우저(인사팀 백오피스, Flutter 앱이 연 시스템 브라우저)는 Keycloak에 직접 접속하지 않고, Gateway의 Keycloak 라우트를 거쳐 접속합니다.
- 별도 IAM 호스트(예: `iam.pmart.test`)로 Keycloak을 직접 노출하는 방식은 쓰지 않습니다.
- Docker Compose에서 외부(호스트)에 포트를 공개하는 진입점은 Gateway입니다. Keycloak 포트는 외부에 공개하지 않습니다.
- **서버 간 호출은 Gateway를 경유하지 않습니다.** Employee Auth Service가 Keycloak을 호출할 때(Token Endpoint, JWKS, 부팅 시 테스트 계정 생성 등)와 Employee Service가 Keycloak Admin REST API를 호출할 때는 Compose 내부 네트워크에서 **Keycloak(`http://keycloak:8080`)을 직접 호출**합니다. Gateway의 Keycloak 라우트는 브라우저 요청 전용입니다.
- Keycloak Admin REST API와 관리 콘솔은 Gateway 라우트로 노출하지 않습니다.
- **Employee Auth Service의 JWKS(PMart 토큰 공개키)도 내부 직접 호출입니다.** Gateway와 샘플 업무 API는 Compose 내부 네트워크에서 `http://employee-auth:8080`의 JWKS 엔드포인트를 직접 호출합니다. JWKS 엔드포인트는 Gateway로 노출하지 않습니다.
- Keycloak 라우트 예시와 설정 주의 사항은 [`docs/architecture/05-gateway-routing.md`](docs/architecture/05-gateway-routing.md)에 있습니다.

## PMart JWT 형식 — 확정

- 서명 알고리즘은 **RS256**(RSA 2048bit)이고, 헤더에 `kid`를 넣습니다.
- 서명키는 Employee Auth Service가 부팅할 때 PostgreSQL에 없으면 만들어 저장하고, 재시작해도 같은 키를 씁니다.
- Access Token 수명은 **15분**입니다.
- 클레임은 `iss`, `sub`(사번), `aud`, `iat`, `exp`, `jti`, `user_type`(`EMPLOYEE`), `roles`, `identity_provider`, `external_subject`(Keycloak UUID)입니다. 개인정보는 넣지 않습니다.
- 테스트 범위의 역할은 `EMPLOYEE`, `HR_ADMIN`이고, `aud` 값은 `pmart-employee-auth`, `pmart-employee-service`, `pmart-sample-api`입니다.
- 상세 형식과 검증 규칙은 [`docs/architecture/04-tokens-and-jwt.md`](docs/architecture/04-tokens-and-jwt.md)에 있습니다.

## 테스트용 인사팀 백오피스 계정: Employee Auth Service가 부팅 시 생성 — 확정

- **테스트 용도**로, 인사팀 백오피스에 로그인할 계정(아이디·비밀번호)을 **Employee Auth Service가 부팅할 때 생성**합니다.
- 부팅이 끝나면 이 계정으로 **바로 인사팀 백오피스에 로그인할 수 있어야** 합니다. 로그인에 필요한 것(Keycloak 사용자, 비밀번호, 인사팀 권한, 로그인 시 확인하는 직원 정보)은 모두 Employee Auth Service가 부팅 과정에서 준비합니다.
- 아이디·비밀번호는 `.env` 환경변수(예: `HR_TEST_USERNAME`, `HR_TEST_PASSWORD`)로 받습니다.
- 재부팅해도 계정이 중복 생성되지 않게 합니다.
- 테스트용 계정이므로 첫 로그인 때 비밀번호 변경을 강제하지 않습니다.

## API Gateway 내장 서버: Netty — 확정

- Gateway도 **Spring Boot 4.x**로 만들고, Spring Cloud Gateway는 Spring Boot 4와 호환되는 버전을 사용합니다.
- Spring Cloud Gateway의 내장 서버는 **Netty**를 사용합니다. 즉 **WebFlux 기반(reactive) Spring Cloud Gateway**로 만듭니다.
- Tomcat 등 서블릿 컨테이너 기반인 **Spring Cloud Gateway Server Web MVC 방식은 사용하지 않습니다.**
- Gateway 프로젝트에 서블릿 스택 의존성(`spring-boot-starter-web` 계열, Tomcat)을 추가하지 않습니다. 서블릿 스택이 클래스패스에 들어오면 Netty 대신 서블릿 방식으로 기동되거나 기동에 실패할 수 있습니다.
- Gateway의 보안 설정도 reactive API로 작성합니다(예: `SecurityWebFilterChain`, `ReactiveJwtDecoder`). 서블릿용 `SecurityFilterChain`, `JwtDecoder` 예제를 그대로 쓰지 않습니다.
- Gateway starter의 정확한 artifact 이름은 Spring Boot 4와 호환되는 Spring Cloud 버전의 공식 문서로 확인합니다.
- 이 규칙은 Gateway에만 적용합니다. Employee Auth Service, Employee Service, 샘플 업무 API는 Spring Web MVC 기반 RESTful API 서버입니다.

## 데이터베이스: PostgreSQL — 확정

- **데이터베이스가 필요한 경우 PostgreSQL을 사용합니다.** MySQL, MariaDB, H2, SQLite 등 다른 DB를 쓰지 않습니다.
  - Keycloak: 개발 모드 기본값인 H2 대신 PostgreSQL에 연결합니다.
  - Employee Service: 직원 정보, Keycloak UUID ↔ 사번 매핑, 계정 발급 상태
  - Employee Auth Service: PMart Refresh Token 등 영속 저장이 필요한 데이터
  - 샘플 업무 API: DB가 필요할 때만
  - Roundcube: 웹메일 DB도 PostgreSQL로 설정합니다.
- PostgreSQL도 Docker Compose 서비스로 실행합니다. 하나의 PostgreSQL 컨테이너 안에 **구성 요소별 DB와 전용 계정**을 따로 만들고, 서비스는 자기 DB에만 접근합니다. (예: `keycloak`, `employee`, `employee_auth`, `roundcube`)
- 테스트 코드도 H2 같은 다른 DB로 대체하지 않고 PostgreSQL에서 실행합니다(예: Testcontainers 또는 Compose의 PostgreSQL).
- **로그인 트랜잭션(`state`·`nonce`·PKCE `code_verifier`)도 PostgreSQL에 저장합니다.** Redis는 사용하지 않습니다.
  - Employee Auth Service의 DB(`employee_auth`)에 저장하고, 만료 시간을 함께 기록합니다.
  - Callback에서 한 번 사용하면 바로 삭제합니다(재사용 방지).
  - 사용되지 않고 만료된 값은 주기적으로 정리합니다.

## 실행 환경: Docker Desktop + Docker Compose — 확정

- **Flutter 앱을 제외한 모든 아티팩트는 Docker Desktop에서 Docker Compose로 실행합니다.**
  - 대상: Keycloak, PostgreSQL, Spring Cloud Gateway, Employee Auth Service, Employee Service, 샘플 업무 API, 인사팀 백오피스(React SPA), Docker Mailserver, Roundcube
  - 각 Spring Boot 서비스와 인사팀 백오피스는 Dockerfile로 이미지를 빌드해 Compose 서비스로 띄웁니다. 로컬 IDE에서 직접 실행하는 것을 기본 실행 방법으로 삼지 않습니다.
  - `docker compose up` 한 번으로 전체 환경이 뜨도록 구성합니다. 기동 순서는 `healthcheck`와 `depends_on: condition: service_healthy`로 맞춥니다.
- **Flutter 앱은 Compose 밖**(에뮬레이터 또는 실기기)에서 실행합니다.
  - Flutter 앱은 컨테이너 서비스 이름(`keycloak`, `gateway` 등)으로 접근할 수 없습니다. 호스트 PC의 LAN IP, 내부 DNS 이름, 또는 Android 에뮬레이터의 `10.0.2.2`처럼 **호스트에 공개된 포트**로 Gateway에 접근합니다.
- 컨테이너끼리는 Compose 네트워크의 **서비스 이름**으로 통신하고, 외부(브라우저·Flutter)에는 필요한 포트만 공개합니다.
- **호스트명 주의**: 브라우저가 보는 Keycloak 주소(예: Gateway 경유 `/iam`)와 컨테이너 내부 백채널 주소(예: `http://keycloak:8080`)가 다르면 ID Token `iss` 검증이 실패할 수 있습니다. Keycloak `hostname` 관련 설정과 Employee Auth Service의 issuer·Token Endpoint 설정을 함께 설계합니다.
- 비밀 값은 Compose의 `.env`(Git 제외)로 주입합니다.

## 절대 깨면 안 되는 설계 원칙

다음은 **확정된 결정**입니다. 코드나 문서에서 이와 다르게 구현하자는 제안은 먼저 사용자에게 확인합니다.

1. **PMart의 Auth Service는 3개** — Customer Auth / Rider Auth / Employee Auth. 하나로 합치지 않습니다. (이 저장소에서는 Employee Auth만 구현)
2. **Keycloak은 직원 전용**입니다. 고객·라이더 인증에 Keycloak을 도입하지 않습니다.
3. **OIDC Client는 Employee Auth Service**(Confidential Client)입니다. Flutter 앱, 인사팀 백오피스(React SPA), Gateway를 Keycloak Client로 만들지 않습니다.
4. 직원 로그인은 **Authorization Code Grant + PKCE(S256)**, `scope=openid` 포함.
   - `grant_type=password`(Direct Access Grants), Implicit Flow, Flutter 임베디드 WebView는 사용하지 않습니다.
5. **업무 API에는 PMart 자체 JWT만 사용**합니다. Keycloak의 Access Token·ID Token·Refresh Token을 업무 API Bearer Token으로 쓰지 않습니다.
6. Employee Service는 Keycloak을 **Admin REST API**로만 다룹니다. Keycloak DB 테이블을 직접 읽거나 쓰지 않습니다.
7. JWT 검증 시 **허용 발급자 목록(allow-list)**을 사용합니다. 토큰 안의 임의 `iss`를 따라 JWKS를 가져오지 않습니다. `aud`, 사용자 유형, 권한도 함께 검사합니다.
8. PMart Access/Refresh Token을 **URL 쿼리·딥링크에 싣지 않습니다**. Flutter 복귀는 일회성 ticket 교환을 사용하며, ticket은 단기 만료·단일 사용·로그인 트랜잭션과 암호학적으로 결속되어야 합니다.

## 작업 규칙

- **문서 언어**: 한국어. 코드 식별자·프로토콜 용어(OIDC, PKCE, `iss` 등)는 원문 유지.
- **상태 표기**: 설계 문서의 각 항목은 `확정` / `권장` / `논의된 배포안` / `미결정`으로 구분합니다. 미결정 항목을 확정처럼 서술하지 않습니다. 미결정 목록은 [`docs/open-questions.md`](docs/open-questions.md)에 있습니다.
- **예시 값**: 도메인(`*.pmart.test`), 포트, Client ID, IP는 모두 예시입니다. 실제 값으로 단정하지 않습니다.
- **버전 고정**: Keycloak·Roundcube·Docker Mailserver 이미지는 `latest`를 쓰지 않고 검증한 버전으로 고정합니다. Keycloak 설정·Import 동작은 버전마다 다르므로 해당 버전 문서로 확인합니다.
- **비밀 정보**: Client Secret, DB 비밀번호, 관리자 자격 증명은 `.env` / Secret으로만 관리하고 Git에 커밋하지 않습니다.
- **`--import-realm`은 최초 생성용**입니다. 이미 있는 Realm은 Import가 건너뛸 수 있으므로, JSON 수정 후 재시작으로 설정이 갱신된다고 가정하지 않습니다.
- **Resource Server Starter 의존성**은 이름만 보고 추가·제거하지 않습니다. 서비스별 `SecurityFilterChain`, `JwtDecoder`, `gradle dependencyInsight` 결과를 근거로 판단합니다.
- 교육 프로젝트이므로 구현할 때 **왜 그렇게 하는지**를 주석·문서로 남깁니다. 보안 검증(인증서 검증, `state`/`nonce` 검사 등)을 실습 편의를 위해 끄지 않습니다.

## 문서 지도

| 문서 | 내용 |
|---|---|
| [docs/README.md](docs/README.md) | 문서 목록과 읽는 순서 |
| [docs/architecture/01-system-overview.md](docs/architecture/01-system-overview.md) | 시스템 경계, 서비스별 책임, 데이터 소유권 |
| [docs/architecture/02-keycloak-realm-and-clients.md](docs/architecture/02-keycloak-realm-and-clients.md) | Realm·Client 설계와 권장 설정 |
| [docs/architecture/03-employee-login-flow.md](docs/architecture/03-employee-login-flow.md) | Authorization Code + PKCE 로그인 흐름, Flutter 앱과 인사팀 백오피스의 차이 |
| [docs/architecture/04-tokens-and-jwt.md](docs/architecture/04-tokens-and-jwt.md) | ID Token, PMart JWT, JWKS, Resource Server |
| [docs/architecture/05-gateway-routing.md](docs/architecture/05-gateway-routing.md) | Gateway와 Keycloak 통신, 공개 경로 |
| [docs/architecture/06-employee-provisioning.md](docs/architecture/06-employee-provisioning.md) | 입사 시 Keycloak 계정 자동 발급 |
| [docs/infrastructure/keycloak-setup.md](docs/infrastructure/keycloak-setup.md) | Keycloak 서버 설정, Realm Import, DB |
| [docs/infrastructure/lan-mail.md](docs/infrastructure/lan-mail.md) | LAN 메일 서버·Roundcube 실습 구성 |
| [docs/decisions.md](docs/decisions.md) | 채택·비채택 결정과 이유 |
| [docs/open-questions.md](docs/open-questions.md) | 미결정·검증 필요 항목 |
| [docs/roadmap.md](docs/roadmap.md) | 훈련생 구현 순서와 체크리스트 |
| [docs/glossary.md](docs/glossary.md) | 용어 정리 |

## 저장소 구조

```text
compose.yaml                     전체 실행 (Flutter 제외)
.env.example                     환경변수 예시 (.env는 Git 제외)
infra/postgres/init/             DB·계정 생성 스크립트 (최초 기동 시 1회)
infra/keycloak/import/           pmart-employees Realm JSON (최초 Import)
services/gateway/                Spring Cloud Gateway (WebFlux, Netty) — 외부 포트 8080
services/employee-auth/          Employee Auth Service (OIDC Client, PMart JWT 발급, JWKS)
services/employee-service/       Employee Service (직원 정보, Keycloak 계정 발급)
services/sample-api/             샘플 업무 API (PMart JWT 검증 확인용)
apps/hr-backoffice/              인사팀 백오피스 React SPA (Nginx) — 외부 포트 3000
scripts/e2e-test.sh              통합 검증 스크립트
docs/verification-log.md         검증 기록 (이 프로젝트의 핵심 결과물)
```

## 실행·검증 명령

```bash
cp .env.example .env              # 최초 1회, 값을 바꿔서 사용
docker compose up -d --build      # 전체 기동
bash scripts/e2e-test.sh          # 통합 검증 (Git Bash)
docker compose logs -f employee-auth
docker compose down               # 중지 (DB 유지)
docker compose down -v            # 중지 + DB 삭제 (Realm·테스트 계정이 다시 생성됨)
```

- 백오피스: http://localhost:3000 — 테스트 계정은 `.env`의 `HR_TEST_USERNAME` / `HR_TEST_PASSWORD`
- 서비스 하나만 다시 빌드: `docker compose up -d --build employee-auth`
- 로컬 컴파일 확인: `cd services/<서비스> && ./gradlew compileJava`
- 백오피스 개발 서버: `cd apps/hr-backoffice && npm run dev` (포트 3000, Gateway는 Compose로 실행)
- Keycloak 관리: 외부 포트가 없으므로 `docker compose exec keycloak /opt/keycloak/bin/kcadm.sh ...` 사용

## 구현 규칙

- 각 Spring Boot 서비스는 `/error`를 `permitAll`로 열어 둡니다. 막으면 모든 오류가 403으로 바뀝니다.
- 하위 서비스(Employee Auth, Employee Service, 샘플 API)는 CSRF·CORS를 끄고 `Authorization: Bearer`만 받습니다.
- Employee Service 내부 API(`/internal/**`)는 Gateway 라우트가 없으며 `X-Internal-Api-Key`로 보호합니다.
- 역할(`EMPLOYEE`, `HR_ADMIN`)은 Employee Service DB에 둡니다. Keycloak에는 역할을 두지 않습니다.
- 설계와 다른 결과나 새로 알게 된 함정은 `docs/verification-log.md`에 기록합니다.

## 문서 유지 관리

- 설계가 바뀌면 원본 설계 문서와 해당 `docs/` 문서를 함께 수정하고, 결정이 바뀐 경우 `docs/decisions.md`에 기록합니다.
- 미결정 항목이 결정되면 `docs/open-questions.md`에서 해당 항목을 `docs/decisions.md`로 옮깁니다.
- 코드가 추가되면 이 파일에 빌드·실행·테스트 명령과 실제 디렉터리 구조를 추가합니다.
