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
| `pmart-employee-auth-bootstrap` | Employee Auth Service가 부팅 시 테스트용 인사팀 계정 생성 (검증 프로젝트 전용) | Client Credentials | Confidential / 서비스 계정 |
| `pmart-webmail` | Roundcube 웹메일 로그인 (D22) | Authorization Code + PKCE(S256) | Confidential / Client Secret |

**모든 Client는 Full Scope Allowed OFF** (확정, D23). Keycloak 26에서 사용 중단(deprecated)된 스위치이고 새 Client의 기본값이 ON이므로 Realm JSON에 `"fullScopeAllowed": false`를 명시합니다. ON이면 사용자·Service Account에 매핑된 역할이 범위 제한 없이 모두 토큰에 실리고, 토큰을 발급할 때마다 경고 로그가 남습니다. 스위치가 제거되면 OFF처럼 동작하므로, 필요한 역할은 Client Role Scope Mapping에 미리 적어 둬야 합니다.

### `pmart-employee-auth` 권장 설정

| 항목 | 값 |
|---|---|
| Client Authentication | ON |
| Standard Flow | ON |
| PKCE Method | S256 |
| Implicit Flow | OFF |
| Direct Access Grants | OFF |
| Service Accounts | OFF |
| Full Scope Allowed | OFF (Role Scope Mapping 없음 — ID Token만 검증하고 Keycloak Access Token의 역할은 쓰지 않음) |
| Authorization (Authorization Services) | OFF |
| Valid Redirect URIs | Employee Auth Callback URL만 정확히 등록 (예: `https://api.pmart.test/employee-auth/callback`) |

### `pmart-employee-service` 권장 설정

| 항목 | 값 |
|---|---|
| Client Authentication | ON |
| Service Accounts | ON |
| Standard Flow | OFF |
| Direct Access Grants | OFF |
| Full Scope Allowed | OFF |
| Service Account 역할 매핑 | `realm-management` → `manage-users`만 |
| Client Role Scope Mapping | `realm-management` → `manage-users`만 |

`pmart-employee-auth-bootstrap`도 같은 설정입니다.

토큰에 실리는 역할은 **Service Account 역할 매핑 ∩ Client Role Scope Mapping**이고, Admin REST API는 토큰의 역할로 권한을 판단합니다(Keycloak 26.8.1에서 확인: 역할 매핑에 `manage-users`가 있어도 Scope가 `query-users`뿐이면 사용자 생성이 403). Employee Service가 쓰는 작업(사용자 조회·생성, 비밀번호 설정, 비활성화, 세션 종료)은 모두 `manage-users`만으로 동작하므로 `view-users`·`query-users`는 주지 않습니다.

참고: `manage-users`는 신규 계정 생성 전용 권한이 아니라 Realm 전체 사용자에 대한 넓은 관리 권한입니다(그룹 목록 조회도 허용됨을 확인). 더 좁히려면 Fine-Grained Admin Permissions가 필요하며 이 검증 범위에서는 다루지 않습니다.

## 주의

- **`Authorization Enabled`는 Authorization Code Flow를 켜는 옵션이 아닙니다.** Keycloak Authorization Services(리소스·정책 기반 인가) 기능이므로 필요하지 않으면 끕니다.
- 직원 앱이 여러 개여도 공통 Employee Auth Service가 **하나의 Client Registration**으로 로그인을 중앙화할 수 있습니다. 앱별 Client 분리는 Redirect URI, 보안 정책, 인증 격리 요구에 따라 결정합니다.
- 로그인용 Client와 관리용 Client를 하나로 합치지 않습니다. 권한 범위가 다릅니다.
