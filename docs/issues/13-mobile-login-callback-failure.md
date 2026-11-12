# #13 앱 로그인 Callback 실패 경로 — 조치 방안 (미결정)

- **상태**: 미결정 ([open-questions.md](../open-questions.md) #13)
- **발견**: 2026-11-12, 실기기(Galaxy Z Flip SM-F700N · Android 13) 재검증 중. 재현 기록은 [verification-log.md](../verification-log.md)의 "2026-11-12 — 실기기 재검증" → 결함 1·2
- **관련 결정**: D20(Flutter 앱 로그인). 아래 추천안을 채택하면 D20을 수정하는 것이므로 [decisions.md](../decisions.md)에 기록해야 함
- **관련 코드**: `services/employee-auth/.../login/AuthController.java`(`callback`, `loginFailed`), `KeycloakOidcClient.java`(`exchangeAndVerify`, `endKeycloakSession`), `apps/employee_mobile/lib/auth_service.dart`

## 1. 현상

앱 로그인이 Callback에서 `invalid_state`로 실패했습니다. 예를 들어 Custom Tab의 Keycloak 화면에서 5분 넘게 머문 뒤 로그인한 경우입니다.

| 결함 | 현상 |
|---|---|
| ① Keycloak 세션 잔존 | 다음 앱 로그인이 **Keycloak 로그인 화면 없이(비밀번호 없이)** 바로 완료됨 (사일런트 로그인) |
| ② 실패 시 잘못된 Redirect | Custom Tab이 앱으로 돌아오지 않고 `http://localhost:3000/?login_error=invalid_state`(백오피스)로 이동 → 기기에서 `ERR_CONNECTION_REFUSED` |

## 2. 원인

| 결함 | 원인 |
|---|---|
| ① | D20의 "앱 로그인 직후 Keycloak 세션 종료"는 `exchangeAndVerify()` 안에서 **Code 교환으로 받은 Keycloak Refresh Token**으로 로그아웃하는 방식. Code 교환 **전에** 실패하면(`invalid_state`, 교환 오류) 세션을 끊을 수단이 없어, 기기 Chrome에 Keycloak SSO 세션 쿠키가 남음 |
| ② | 앱과 백오피스가 같은 Callback 경로(`/employee-auth/callback`)를 씀. 로그인 트랜잭션이 없으면 클라이언트를 구분할 정보가 없어 `loginFailed()` → `BACKOFFICE_ORIGIN`으로 보냄 |
| 빈도를 키우는 요인 | 우리 로그인 트랜잭션 수명은 **5분**(`application.yml`의 `login-transaction-ttl: 5m`). Keycloak 로그인 제한 시간(`accessCodeLifespanLogin`)은 Realm JSON에 설정이 없어 **기본값 30분**. 5~30분 사이에 로그인하면 Keycloak은 성공하고 우리 쪽은 `invalid_state`. 첫 로그인의 임시 비밀번호 변경처럼 시간이 걸리는 경우 특히 잘 생김 |

**영향**: D20은 "앱 로그인 뒤 기기 브라우저에 Keycloak 세션이 남지 않는다"를 전제로 합니다. 이 전제가 실패 경로에서 깨집니다. 직원 공용 기기라면 다음 사람이 이전 직원 계정으로 비밀번호 없이 로그인할 수 있습니다.

## 3. 결함 ② 조치 후보 — 실패를 앱으로 돌려보내기

| 방안 | 내용 | 평가 |
|---|---|---|
| **A. 앱 전용 Callback 경로 분리 (추천)** | 앱은 `/employee-auth/mobile/callback`, 백오피스는 기존 `/employee-auth/callback`. Keycloak `pmart-employee-auth`의 `redirectUris`에 두 경로를 모두 등록. 인가 요청과 Code 교환에 같은 `redirect_uri`를 사용. 트랜잭션이 없어도 **경로로 구분**해 `pmart-employee://login-callback?error=invalid_state`로 복귀 | 앱은 이미 `error` 파라미터를 처리함(`auth_service.dart`). 수정 범위가 작고 원인을 없앰 |
| B. `state`에 클라이언트 표시 (예: `m.` 접두사) | 서명되지 않은 값이지만, 바뀌어도 실패 시 이동할 곳만 달라짐 | 동작은 하지만 `state`에 의미를 싣는 변칙이라 교육용으로 부적절 |
| C. 중립 오류 페이지 | Employee Auth가 HTML 화면 제공 | "Employee Auth는 화면을 만들지 않는다"는 원칙과 충돌. 비추천 |

## 4. 결함 ① 조치 후보 — 세션이 남아도 비밀번호 없이 로그인되지 않게

| 방안 | 내용 | 평가 |
|---|---|---|
| **A. `auth_time` 검사 (추천, 근본 조치)** | 앱 로그인에서 Code를 교환한 뒤 ID Token의 `auth_time`(실제로 인증한 시각)이 **이번 로그인 트랜잭션 생성 시각보다 이전이면** 남아 있던 세션으로 로그인된 것으로 판단. ① 받은 Keycloak 토큰으로 세션 종료(기존 코드) → ② 앱에 `error=reauth_required` → ③ 앱이 자동으로 로그인 재시작. 두 번째 시도에서는 세션이 없어 일반 로그인 화면이 나오고 계정도 바꿀 수 있음 | **실패 원인과 상관없이** 사일런트 로그인을 막음. D20에서 `prompt=login`을 뺀 이유(아이디 칸 고정)도 피함. **확인 필요**: Keycloak 26.8.1 ID Token에 `auth_time`이 들어가는지, 로그인 트랜잭션 테이블에 생성 시각 저장(현재는 `expires_at`만 있음) |
| **B. 제한 시간 맞추기 (보조, 빈도 감소)** | Realm JSON에 `accessCodeLifespanLogin`을 명시하고 **우리 트랜잭션 수명이 그보다 길게** 설정 (예: Keycloak 10분, 트랜잭션 15분). 시간이 초과되면 Keycloak이 먼저 자기 화면에서 만료를 처리 | 설정 변경만으로 `invalid_state` 발생 자체를 줄임. 근본 조치가 아니므로 A와 함께 사용. **확인 필요**: 임시 비밀번호 변경(required action) 단계에도 이 제한 시간이 적용되는지 |
| C. 임시(ephemeral) 브라우저 세션 | `FlutterWebAuth2Options(preferEphemeral: true)`로 쿠키를 남기지 않는 브라우저에서 로그인 | iOS(`ASWebAuthenticationSession`)에서는 확실. **Android Custom Tabs에서 쿠키가 실제로 분리되는지는 flutter_web_auth_2 5.1.0·Chrome 버전에 따라 달라 실기기 검증 필요**. 된다면 가장 깔끔한 보완책 |
| D. 실패 시 Keycloak 로그아웃 주소로 이동 | `invalid_state`면 `end_session`으로 Redirect | `id_token_hint`가 없어 Keycloak이 로그아웃 확인 화면을 띄움. 사용자가 누르지 않으면 세션이 남음. 비추천 |
| E. `prompt=login` · `max_age=0` | 매번 다시 인증 | D20에서 아이디 칸이 이전 계정으로 고정되는 문제로 뺀 방식. 비추천 |
| F. Realm SSO 세션 수명 단축 | 세션 자체를 빨리 만료 | 백오피스·웹메일 SSO까지 영향. 남는 시간 동안은 여전히 사일런트 로그인. 비추천 |

## 5. 추천 조합과 진행 순서 (채택 시)

1. **결함 ② A**: 앱 전용 Callback 경로 분리 → 실패하면 앱으로 복귀해 메시지 표시
2. **결함 ① B**: Keycloak 로그인 제한 시간 < 트랜잭션 수명 → `invalid_state` 감소
3. **결함 ① A**: `auth_time` 검사 → 세션이 남아 있어도 비밀번호 없이 로그인되지 않음
4. (선택) **결함 ① C**: Android에서 ephemeral 동작을 실기기로 확인. 되면 보완책으로 적용
5. **검증**
   - 자동화 테스트: Callback 경로 분리, `auth_time` 검사 단위 테스트 추가 후 `./gradlew test`
   - 실기기: 5분 넘게 기다렸다 로그인해 재현 → 앱으로 오류 복귀 → 재로그인 때 비밀번호를 묻는지 → 다른 계정으로 바꿀 수 있는지
   - 새 환경(`docker compose down -v` → `up`)에서 e2e 통과

## 6. 결정이 필요한 것

- [ ] 결함 ② 조치 방안 선택 (추천: A)
- [ ] 결함 ① 조치 방안 선택 (추천: A + B, C는 검증 후 선택)
- [ ] D20 수정으로 기록할지, 새 결정(D24)으로 기록할지
- [ ] 직원 기기 정책: 개인 기기 / 공용 기기. 공용 기기라면 결함 ① A는 필수
