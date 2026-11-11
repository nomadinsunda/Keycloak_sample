# LAN 메일 — IAM 계정으로 웹메일 SSO (D22)

## 목적과 범위

- 외부 인터넷 메일이 아닌 **LAN 내부 직원 간 이메일 송수신**
- **IAM 계정 ID(`계정명@pmart.test`)가 곧 메일 주소**이며, 직원은 **IAM 계정(Keycloak) 하나로** 웹메일에 로그인
- Keycloak 비밀번호 설정·재설정 메일 발송
- 범위 제외: 스팸 방어, 공인 메일 평판, SPF/DKIM/DMARC, 백업·고가용성

## 구성 (모두 Docker Compose)

| 서비스 | 이미지 | 역할 | 외부 포트 |
|---|---|---|---|
| `mail-init` | `alpine:3.22.8` (1회 실행) | 내부 CA·메일 서버 인증서 생성, `noreply` 계정 생성, 외부 발송 차단 설정 복사 | 없음 |
| `mailserver` | `mailserver/docker-mailserver:16.0.1` | SMTP(Postfix 587 STARTTLS), IMAP(Dovecot 143 STARTTLS). 로그인은 Keycloak 토큰(OAuth2)으로 검증 | 없음 (내부 전용) |
| `roundcube` | `roundcube/roundcubemail:1.7.5-apache` | 웹메일. Keycloak 로그인(OIDC + PKCE), DB는 PostgreSQL `roundcube` | **8088** |

## 로그인 흐름

```text
직원 브라우저 → http://localhost:8088 (Roundcube)
  → Keycloak 로그인 화면 (Gateway /iam, Client: pmart-webmail, PKCE S256)
  → IAM 계정 ID + 비밀번호
  → Roundcube가 Code를 토큰으로 교환 (Keycloak 내부 주소 직접 호출, Gateway 경유 안 함)
  → Roundcube가 Access Token으로 IMAP·SMTP에 OAUTHBEARER/XOAUTH2 로그인 (STARTTLS)
  → Dovecot이 토큰을 Keycloak userinfo로 검증 (내부 직접 호출), userinfo의 email == 로그인 계정이어야 통과
```

- 퇴사(Keycloak 계정 비활성화) 시 Keycloak 로그인이 막히므로 웹메일에도 로그인할 수 없음
- 메일함 자체는 남겨 둠(메일 보존). 퇴사 후에도 메일은 받을 수 있음

## 메일함 생성 (IAM 계정과 연동)

- Docker Mailserver는 **계정이 미리 있어야 메일을 받음**(OAuth2 로그인만으로는 메일함이 생기지 않음)
- **직원 등록 시** Employee Service가 IAM 계정 발급 직후 메일 서버 계정 파일(`postfix-accounts.cf`, 공유 볼륨 `mail-config`)에 IAM 계정 ID를 추가 → 메일 서버가 변경을 감지해 메일함 생성(수 초)
- **부팅 시** Employee Service가 재직 중인 IAM 계정 직원의 메일함을 모두 확인해 없으면 만듦(동기화)
- 계정 파일의 비밀번호는 아무도 모르는 무작위 값의 해시. 메일 로그인은 Keycloak 토큰으로만 가능
- 테스트 프로젝트용 단순 방식(공유 파일 직접 수정). 운영이라면 SCIM·LDAP 같은 정식 프로비저닝 필요

## Keycloak 메일 발송

- Realm SMTP: `mailserver:587`, STARTTLS, 발신자·SMTP 계정 `noreply@pmart.test`(비밀번호 `MAIL_NOREPLY_PASSWORD`)
- Keycloak은 `KC_TRUSTSTORE_PATHS=/certs/ca.crt`로 내부 CA를 신뢰
- 확인: `execute-actions-email`로 보낸 "Update Your Account" 메일이 메일함에 도착

## 보안 설정

| 항목 | 내용 |
|---|---|
| TLS | 내부 CA가 발급한 인증서(SAN: `mailserver`, `mail.pmart.test`). Roundcube·Keycloak 모두 **인증서 검증을 끄지 않고** 내부 CA로 검증 |
| 평문 로그인 | 다른 컨테이너에서 TLS 없이 접속하면 Dovecot이 `LOGINDISABLED`(로그인 방식 미제공). 단, 메일 서버 컨테이너 안에서 자기 자신에게 접속하면 로컬 연결로 보고 허용됨 |
| 사칭 방지 | `SPOOF_PROTECTION=1`: 로그인 계정과 다른 보내는 사람 주소 거부 |
| 외부 발송 차단 | `postfix-main.cf`의 `default_transport`·`relay_transport`를 error로 설정 → `pmart.test` 외 도메인은 반송 |
| 포트 | SMTP·IMAP은 외부에 공개하지 않음. 직원은 웹메일(8088)로만 사용 |

## 운영 명령

```bash
# 메일함 목록
MSYS_NO_PATHCONV=1 docker compose exec mailserver doveadm user '*'
# 메일함 내용 (제목)
MSYS_NO_PATHCONV=1 docker compose exec mailserver doveadm fetch -u hr.admin@pmart.test 'hdr.from hdr.subject' mailbox INBOX
# 메일 로그
docker compose logs -f mailserver
```
