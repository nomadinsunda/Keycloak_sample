# 미결정·검증 필요 항목

방향은 논의했지만 **실제 코드·배포 검증 또는 최종 정책 결정이 필요한 항목**입니다. 결정되면 [decisions.md](decisions.md)로 옮기고 여기서는 `결정됨`으로 표시합니다.

| # | 주제 | 결정·검증할 내용 | 관련 문서 | 상태 |
|---|---|---|---|---|
| 1 | Gateway ↔ Keycloak 경로 설정 | Keycloak을 Gateway 뒷단에 두는 것은 확정(D7). 남은 것: Keycloak `hostname`·relative path·프록시 헤더 설정, Admin API·관리 콘솔 비공개 처리 | [05](architecture/05-gateway-routing.md) | 검증 필요 |
| 2 | Employee Auth 로그인 API | 인증 시작 URL 반환 방식, Callback 경로, 만료 시간·재시도 처리. (`state`·`nonce`·PKCE 저장소는 PostgreSQL로 확정, D13) | [03](architecture/03-employee-login-flow.md) | 미결정 |
| 3 | Flutter 로그인 완료 | 확정 (D20): LAN 테스트 환경이라 App Link 대신 커스텀 스킴, ticket은 app_verifier에 결속·60초·단일 사용, 토큰은 보안 저장소. **실제 App Link/Universal Link(HTTPS 도메인 필요)는 운영 환경에서 별도 검증 필요** | [03](architecture/03-employee-login-flow.md) | 결정됨 |
| 4 | React SPA(인사팀 백오피스) 로그인 완료 | PMart JWT를 `Set-Cookie`로 전달(D15), CORS(D16)·CSRF 방어(D17)는 Gateway에서 적용 | [03](architecture/03-employee-login-flow.md) | 결정됨 |
| 5 | 자체 JWT 형식 | RS256, 클레임, 수명 15분, 키 보관·회전 방식 확정 (D14) | [04](architecture/04-tokens-and-jwt.md) | 결정됨 |
| 6 | Refresh Token 정책 | 확정 (D19): 무작위 값·해시 저장, 8시간 고정, Rotation, 재사용 탐지 시 세션 폐기, 퇴사 시 즉시 폐기·Keycloak 계정 비활성화. Flutter용 전달 방식은 10단계에서 | [04](architecture/04-tokens-and-jwt.md) | 결정됨 |
| 7 | 세 Auth Service의 JWKS | JWKS 엔드포인트 구현, 공개키 로딩·캐싱·회전, Gateway·MSA 허용 발급자 설정 | [04](architecture/04-tokens-and-jwt.md) | 미결정 |
| 8 | Resource Server Starter 필요성 | 서비스별 `SecurityFilterChain`, `JwtDecoder`, `dependencyInsight`로 판단. 이름만 보고 제거하지 않음 | [04](architecture/04-tokens-and-jwt.md) | 검증 필요 |
| 9 | Keycloak Realm JSON | 최초 Import의 Client Secret, 역할, 서비스 계정 권한, Keycloak 버전 호환성 | [keycloak-setup](infrastructure/keycloak-setup.md) | 검증 필요 |
| 10 | 메일 실습 | 구현·검증 완료 (D22). 남은 것: 메일 서버 계정 프로비저닝을 공유 파일 대신 정식 방식(SCIM 등)으로, LAN의 다른 PC에서 접속하려면 내부 DNS·CA 배포 필요 | [lan-mail](infrastructure/lan-mail.md) | 결정됨 |
| 11 | 직원 권한 정책 | Keycloak 역할과 PMart 업무 권한의 경계, MFC 단위 접근 통제, 계정·권한 변경 이벤트 처리 | [06](architecture/06-employee-provisioning.md) | 미결정 |
| 12 | **계정 콘솔 이메일 변경 → 다른 직원 메일함 접근 (보안 결함, 2026-11-12 확인)** | 직원이 Keycloak 계정 콘솔(`account-console`, Gateway `/iam` 경유로 접근 가능)에서 이메일을 다른 직원 주소로 바꾸면, 웹메일 토큰의 `email`이 바뀌고 Dovecot(`username_attribute = email`)이 그 주소의 메일함 로그인을 허용함. 원인 조합: user profile `email` 편집 권한에 `user` 포함, `duplicateEmailsAllowed: true`, Dovecot이 바뀔 수 있는 `email`을 계정 이름으로 사용. 조치 방법 결정 필요 | [lan-mail](infrastructure/lan-mail.md), [02](architecture/02-keycloak-realm-and-clients.md) | 미결정 |
