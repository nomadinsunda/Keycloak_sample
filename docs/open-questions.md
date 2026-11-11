# 미결정·검증 필요 항목

방향은 논의했지만 **실제 코드·배포 검증 또는 최종 정책 결정이 필요한 항목**입니다. 결정되면 [decisions.md](decisions.md)로 옮기고 여기서는 `결정됨`으로 표시합니다.

| # | 주제 | 결정·검증할 내용 | 관련 문서 | 상태 |
|---|---|---|---|---|
| 1 | Gateway ↔ Keycloak 경로 설정 | Keycloak을 Gateway 뒷단에 두는 것은 확정(D7). 남은 것: Keycloak `hostname`·relative path·프록시 헤더 설정, Admin API·관리 콘솔 비공개 처리 | [05](architecture/05-gateway-routing.md) | 검증 필요 |
| 2 | Employee Auth 로그인 API | 인증 시작 URL 반환 방식, Callback 경로, 만료 시간·재시도 처리. (`state`·`nonce`·PKCE 저장소는 PostgreSQL로 확정, D13) | [03](architecture/03-employee-login-flow.md) | 미결정 |
| 3 | Flutter 로그인 완료 | App Link/Universal Link 실제 도메인, ticket의 앱 바인딩·재사용 방지, PMart JWT 안전 저장 | [03](architecture/03-employee-login-flow.md) | 미결정 |
| 4 | React SPA(인사팀 백오피스) 로그인 완료 | PMart JWT를 `Set-Cookie`로 전달(D15), CORS(D16)·CSRF 방어(D17)는 Gateway에서 적용 | [03](architecture/03-employee-login-flow.md) | 결정됨 |
| 5 | 자체 JWT 형식 | RS256, 클레임, 수명 15분, 키 보관·회전 방식 확정 (D14) | [04](architecture/04-tokens-and-jwt.md) | 결정됨 |
| 6 | Refresh Token 정책 | 저장소, Rotation, 재사용 탐지, 비활성화·퇴사 시 즉시 폐기, Keycloak 로그아웃 연계 | [04](architecture/04-tokens-and-jwt.md), [06](architecture/06-employee-provisioning.md) | 미결정 |
| 7 | 세 Auth Service의 JWKS | JWKS 엔드포인트 구현, 공개키 로딩·캐싱·회전, Gateway·MSA 허용 발급자 설정 | [04](architecture/04-tokens-and-jwt.md) | 미결정 |
| 8 | Resource Server Starter 필요성 | 서비스별 `SecurityFilterChain`, `JwtDecoder`, `dependencyInsight`로 판단. 이름만 보고 제거하지 않음 | [04](architecture/04-tokens-and-jwt.md) | 검증 필요 |
| 9 | Keycloak Realm JSON | 최초 Import의 Client Secret, 역할, 서비스 계정 권한, Keycloak 버전 호환성 | [keycloak-setup](infrastructure/keycloak-setup.md) | 검증 필요 |
| 10 | 메일 실습 | Docker Mailserver·Roundcube 버전 고정, LAN DNS, TLS 신뢰, 메일 계정 생성 자동화, Keycloak SMTP 연결 | [lan-mail](infrastructure/lan-mail.md) | 검증 필요 |
| 11 | 직원 권한 정책 | Keycloak 역할과 PMart 업무 권한의 경계, MFC 단위 접근 통제, 계정·권한 변경 이벤트 처리 | [06](architecture/06-employee-provisioning.md) | 미결정 |