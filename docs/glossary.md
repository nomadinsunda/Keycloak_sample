# 용어집

| 용어 | 설명 |
|---|---|
| IAM | Identity and Access Management. 계정·인증·권한 관리 |
| IdP | Identity Provider. 사용자 신원을 확인해 주는 시스템. 여기서는 Keycloak |
| OAuth 2.0 | 권한 위임 프레임워크. Access Token 발급 규칙 |
| OIDC | OpenID Connect. OAuth 2.0 위에 **신원 인증**(ID Token)을 더한 표준 |
| Realm | Keycloak의 독립된 사용자·Client·정책 공간. 여기서는 `pmart-employees` |
| Confidential Client | Client Secret을 안전하게 보관할 수 있는 서버 측 Client. 여기서는 Employee Auth Service |
| Authorization Code Grant | 브라우저로 일회성 Code를 받고, 서버가 백채널로 토큰과 교환하는 방식 |
| PKCE | Proof Key for Code Exchange. Code 탈취 공격을 막는 Authorization Code Flow 확장 |
| `code_verifier` / `code_challenge` | PKCE용 원본 비밀값 / 그 SHA-256 해시의 BASE64URL 인코딩 |
| `state` | 인증 요청·응답을 묶어 CSRF를 막는 임의값 |
| `nonce` | 인증 요청과 ID Token을 묶어 재전송을 막는 임의값 |
| ID Token | OIDC에서 "누가 로그인했는지"를 담은 JWT. Client가 검증하며 API 인증용이 아님 |
| Access Token | API 접근에 쓰는 토큰. PMart 업무 API에는 **PMart 자체 Access Token**만 사용 |
| Refresh Token | Access Token 재발급용 장기 토큰 |
| Rotation | Refresh Token 사용 시마다 새 토큰을 발급하고 이전 토큰을 무효화하는 정책 |
| JWT / JWS | JSON Web Token / 서명된 JWT |
| JWK / JWKS | JSON Web Key / 공개키 목록(Key Set). 서명 검증에 사용 |
| `kid` | Key ID. JWT 서명키와 JWKS 키를 대응시키는 식별자 |
| `iss` / `aud` / `sub` | 발급자 / 대상 수신자 / 주체(사용자 식별자) |
| Resource Server | Access Token으로 API 접근을 통제하는 **역할**. 일반 Spring Boot MSA가 수행 |
| Nimbus JOSE + JWT | Java JWT·JWK 처리 라이브러리 |
| SPA | Single Page Application. 페이지 하나를 받은 뒤 화면 전환을 브라우저에서 처리하는 웹 앱. 인사팀 백오피스가 React SPA |
| Vite | 프론트엔드 빌드 도구·개발 서버. React SPA 빌드에 사용 |
| Tailwind CSS / daisyUI | 유틸리티 클래스 기반 CSS 프레임워크 / Tailwind CSS용 컴포넌트 플러그인 |
| BFF | Backend For Frontend. 브라우저 대신 서버가 토큰을 보관하고 세션 쿠키로 통신 |
| 일회성 ticket | Flutter 앱 복귀 후 PMart 토큰과 교환하는 PMart 내부 단기 코드. OAuth Code와 다름 |
| App Link / Universal Link | Android / iOS에서 HTTPS 링크로 앱을 여는 기능 |
| Client Credentials Grant | 사용자 없이 서비스 자신의 자격으로 토큰을 받는 방식. Admin API 호출에 사용 |
| Admin REST API | Keycloak 사용자·Realm 관리용 HTTP API |
| MFC | Micro Fulfillment Center. PMart 물류 거점 단위 |
| SMTP / IMAP | 메일 발송 / 메일함 조회 프로토콜 |
| STARTTLS | 평문 연결을 TLS로 업그레이드하는 방식 |
| SAN | Subject Alternative Name. 인증서가 유효한 호스트명 목록 |
