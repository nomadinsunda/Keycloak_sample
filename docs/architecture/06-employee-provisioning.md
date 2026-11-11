# 06. 직원 입사와 Keycloak 계정 자동 발급

## 업무 흐름 — 권장

```text
인사팀 직원
  → PMart Backoffice(React SPA) 직원 등록
  → Spring Cloud Gateway
  → Employee Service
       ├─ HR 권한 확인
       ├─ PMart PostgreSQL에 직원 정보 저장
       ├─ Keycloak Admin REST API로 직원 IAM 계정 생성
       ├─ Keycloak 사용자 UUID ↔ 사번 매핑 저장
       ├─ (메일 운영 시) 직원 메일함 생성
       └─ 계정 발급 결과·실패 상태 기록
```

- 인사팀은 Keycloak 관리 콘솔이 아니라 **PMart 백오피스**를 사용합니다.
- PMart 백오피스는 **React SPA**(Vite + Tailwind CSS + daisyUI 최신 안정 버전)입니다. 인사팀 직원은 다른 직원과 같은 Keycloak 로그인 흐름으로 로그인하고, SPA는 Keycloak Admin API를 직접 호출하지 않고 Gateway → Employee Service만 호출합니다.
- Employee Service는 Keycloak DB를 직접 수정하지 않고 **Admin REST API**를 사용합니다.
- **Employee Service가 Keycloak을 호출할 때(Client Credentials 토큰 발급, Admin REST API)는 Gateway를 경유하지 않고** Compose 내부 네트워크에서 `http://keycloak:8080`을 직접 호출합니다.

## 테스트용 인사팀 백오피스 계정 — 확정

- **테스트 용도**로, 인사팀 백오피스에 로그인할 계정(아이디·비밀번호)을 **Employee Auth Service가 부팅할 때 생성**합니다.
- 부팅이 끝나면 이 계정으로 바로 인사팀 백오피스에 로그인할 수 있어야 합니다. 로그인에 필요한 것은 모두 Employee Auth Service가 부팅 과정에서 준비합니다.
- 아이디·비밀번호는 `.env` 환경변수(예: `HR_TEST_USERNAME`, `HR_TEST_PASSWORD`)로 받고, 재부팅해도 중복 생성되지 않게 합니다.
- 테스트용 계정이므로 첫 로그인 때 비밀번호 변경을 강제하지 않습니다.

## 분산 작업 설계

직원 DB 저장과 Keycloak 계정 생성은 **단일 트랜잭션이 아닙니다.** 다음을 설계해야 합니다.

- **발급 상태**: 예) `PENDING` → `IAM_CREATED` → `MAIL_CREATED` → `COMPLETED` / `FAILED`
- **멱등성**: 재시도 시 중복 계정이 생기지 않도록 username(사번)으로 존재 여부 확인, `409 Conflict` 처리
- **재시도**: 일시적 오류는 재시도, 영구 오류는 실패 상태로 기록하고 관리자 확인
- **보상**: 후속 단계가 영구 실패하면 생성된 계정 비활성화 등

## 관리 API 토큰 (Client Credentials)

```http
POST /realms/pmart-employees/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=pmart-employee-service
&client_secret=...
```

직원 로그인용 Authorization Code Flow와 **별개**입니다. 서비스 계정에는 `realm-management`의 필요한 사용자 관리 권한만 부여합니다.

## 신규 직원 생성 예시

```http
POST /admin/realms/pmart-employees/users
Authorization: Bearer <SERVICE_ACCOUNT_ACCESS_TOKEN>
Content-Type: application/json

{
  "username": "EMP000123",
  "firstName": "길동",
  "lastName": "홍",
  "email": "hong@pmart.test",
  "enabled": true,
  "requiredActions": ["UPDATE_PASSWORD"]
}
```

- 응답의 `Location` 헤더에서 생성된 사용자 UUID를 얻어 사번과 매핑합니다.
- 사용자 생성과 초기 비밀번호 설정은 **별도 절차**입니다.
  - 메일함이 있으면 `execute-actions-email`로 비밀번호 설정 링크 발송
  - 메일함이 없으면 관리자가 초기 비밀번호를 안전하게 전달하는 절차 필요
- Keycloak 계정 생성이 메일함을 자동으로 만들지는 않습니다 ([LAN 메일](../infrastructure/lan-mail.md)).

## 퇴사·휴직·권한 변경

- Keycloak 사용자 비활성화(`enabled=false`)와 함께 **PMart 자체 Refresh Token·세션도 폐기**합니다.
- 권한 변경 시 이미 발급된 Access Token의 남은 수명 동안 이전 권한이 유효함을 고려합니다 (짧은 수명 유지).
- 이벤트 처리 방식은 [미결정 #6, #11](../open-questions.md)입니다.
