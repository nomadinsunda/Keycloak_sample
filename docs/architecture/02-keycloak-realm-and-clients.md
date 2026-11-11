# 02. Keycloak Realm 및 Client 설계

## Realm — 확정

- Realm 이름: `pmart-employees`
- `master` Realm은 Keycloak 관리용으로만 유지하고, 직원 계정은 업무 Realm에 등록합니다.
- 고객·라이더 Realm은 만들지 않습니다.
- Keycloak은 직원 **IAM 계정**을 발급합니다 (D21). 사용자명(username) = **IAM 계정 ID** `계정명@pmart.test` = Keycloak 이메일. 예: `gildong.hong@pmart.test`
- 사번(예: `EMP000123`)은 인사 정보로만 쓰며 Keycloak 로그인 ID로 쓰지 않습니다.
- Keycloak `sub`(사용자 UUID)를 **안정적인 외부 식별자**로 저장하고 PMart 사번과 매핑합니다.
- 이메일은 IAM 계정 ID와 같은 값으로 항상 채웁니다. 11단계 LAN 메일함을 운영하면 이 주소가 실제 메일함 주소가 됩니다.

## Client — 권장

| Client ID (예시) | 용도 | Grant/Flow | Client 인증 |
|---|---|---|---|
| `pmart-employee-auth` | 직원 로그인 (Employee Auth Service) | Authorization Code + PKCE(S256) | Confidential / Client Secret |
| `pmart-employee-service` | Employee Service의 Admin REST API 호출 | Client Credentials | Confidential / 서비스 계정 |
### `pmart-employee-auth` 권장 설정

| 항목 | 값 |
|---|---|
| Client Authentication | ON |
| Standard Flow | ON |
| PKCE Method | S256 |
| Implicit Flow | OFF |
| Direct Access Grants | OFF |
| Service Accounts | OFF |
| Authorization (Authorization Services) | OFF |
| Valid Redirect URIs | Employee Auth Callback URL만 정확히 등록 (예: `https://api.pmart.test/employee-auth/callback`) |

### `pmart-employee-service` 권장 설정

| 항목 | 값 |
|---|---|
| Client Authentication | ON |
| Service Accounts | ON |
| Standard Flow | OFF |
| Direct Access Grants | OFF |
| 서비스 계정 역할 | `realm-management`에서 필요한 사용자 관리 권한만 최소 부여 |

## 주의

- **`Authorization Enabled`는 Authorization Code Flow를 켜는 옵션이 아닙니다.** Keycloak Authorization Services(리소스·정책 기반 인가) 기능이므로 필요하지 않으면 끕니다.
- 직원 앱이 여러 개여도 공통 Employee Auth Service가 **하나의 Client Registration**으로 로그인을 중앙화할 수 있습니다. 앱별 Client 분리는 Redirect URI, 보안 정책, 인증 격리 요구에 따라 결정합니다.
- 로그인용 Client와 관리용 Client를 하나로 합치지 않습니다. 권한 범위가 다릅니다.
