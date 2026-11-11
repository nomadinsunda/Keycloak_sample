# 훈련생 구현 로드맵

각 단계의 완료 조건을 확인하며 순서대로 진행합니다.

## 1. Keycloak 기동
- [ ] PostgreSQL에 Keycloak 전용 DB·계정 생성
- [ ] 버전을 고정한 Keycloak 컨테이너 기동
- [ ] `pmart-employees` Realm JSON 최초 Import
- [ ] 로그인용 `pmart-employee-auth`, 관리용 `pmart-employee-service` Client 생성
- 완료 조건: Realm의 `/.well-known/openid-configuration` 응답 확인, 두 Client 설정이 [02 문서](architecture/02-keycloak-realm-and-clients.md)와 일치

## 2. 직원 계정 프로비저닝
- [ ] Employee Service에서 Client Credentials로 관리 토큰 획득
- [ ] Admin REST API로 직원 계정 생성, UUID ↔ 사번 매핑 저장
- [ ] 발급 상태·멱등성·재시도 처리
- 완료 조건: 같은 직원을 두 번 등록해도 계정이 하나만 생성됨

## 3. Employee Auth Service OIDC
- [ ] 로그인 시작: `state`, `nonce`, PKCE 생성·저장
- [ ] Callback: `state` 검증, Code 교환
- [ ] ID Token 검증 (서명, `iss`, `aud`, `exp`, `nonce`)
- [ ] 직원 상태·권한 확인 후 PMart JWT 발급, JWKS 엔드포인트 제공
- [ ] 부팅 시 테스트용 인사팀 백오피스 계정 생성
- 완료 조건(테스트 계정): 부팅 직후 이 계정으로 백오피스 로그인 성공, 재기동해도 계정이 하나만 있음
- 완료 조건: 변조된 `state`/`nonce`, 만료된 트랜잭션이 모두 거부됨

## 4. Gateway 라우팅
- [ ] 로그인 시작·Callback·`/iam/**` 경로 인증 예외
- [ ] Admin API 외부 비공개
- [ ] Keycloak 프록시 설정과 `iss` 일치 확인
- 완료 조건: Gateway 경유로 Keycloak 로그인 화면 표시 및 로그인 성공

## 5. 인사팀 백오피스(React SPA) 로그인 실습
- [ ] 브라우저 Redirect 흐름
- [ ] BFF 세션 쿠키 또는 ticket 교환 (미결정 #4 결정 후)

## 6. Flutter 로그인 실습
- [ ] 시스템 브라우저 로그인 (WebView 금지)
- [ ] App Link / Universal Link 복귀
- [ ] 앱 바인딩된 일회성 ticket 교환
- 완료 조건: 탈취된 ticket만으로는 토큰 교환 불가, ticket 재사용 거부

## 7. JWT 검증
- [ ] Gateway·업무 서비스에 발급자별 허용 목록, JWKS, `aud`, 권한 검증
- 완료 조건: 허용되지 않은 발급자·`aud` 토큰 거부

## 8. Refresh / 로그아웃
- [ ] PMart Refresh Token Rotation, 재사용 탐지
- [ ] Keycloak 계정 비활성화 시 PMart 세션·Refresh Token 폐기

## 9. LAN 메일
- [ ] Docker Mailserver(Postfix/Dovecot), Roundcube 기동 (버전 고정)
- [ ] 내부 CA 인증서와 신뢰 설정
- [ ] Keycloak SMTP 연결, 신규 직원 메일함 연동
- 완료 조건: 신규 직원이 Roundcube에서 Keycloak 비밀번호 설정 메일 수신

## 10. 통합 테스트
- [ ] 고객/라이더/직원 토큰의 교차 접근 거부 (고객·라이더 토큰은 테스트용 키로 서명해 만든 것을 사용. Customer/Rider Auth Service는 구현하지 않음)
- [ ] 권한 변경, 재로그인, 만료·재발급
- [ ] 장애·재시도 시나리오
