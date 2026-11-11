# 로컬 LAN 직원 이메일 실습 구성

## 목적과 범위

- 외부 인터넷 메일이 아닌 **LAN 내부 직원 간 이메일 송수신**
- Keycloak 비밀번호 설정·재설정 메일 테스트
- SMTP/IMAP/웹메일, Docker 네트워킹 학습

**범위 제외**: 스팸 방어, 공인 메일 평판, SPF/DKIM/DMARC, 백업·고가용성 등 상용 메일 운영 수준 기능

## 구성 요소

| 구성 요소 | 제품/프로토콜 | 역할 |
|---|---|---|
| SMTP | Postfix / SMTP Submission | 메일 발송·전달 |
| IMAP | Dovecot / IMAP | 메일함 조회·관리 |
| 메일 서버 통합 배포 | Docker Mailserver | Postfix + Dovecot 컨테이너 |
| 웹메일 | Roundcube (Apache 포함 공식 이미지) | 브라우저 메일 UI (웹 서버 + PHP 포함) |
| Keycloak | SMTP Client | 직원에게 설정·재설정 메일 발송 |

- **Roundcube는 SMTP/IMAP 서버가 아니라 웹메일 애플리케이션**입니다.
- `roundcube/roundcubemail`의 Apache 포함 이미지에는 웹 서버와 PHP가 들어 있어 별도 Apache 컨테이너가 필요 없습니다.
- Mailserver, Roundcube, Keycloak은 **별도 컨테이너**로 운영합니다.

## 네트워크 구조

```text
직원 브라우저
    │ HTTP(S) :8088
    ▼
Roundcube (Apache + PHP)
    ├─ SMTP :587 / STARTTLS → Postfix
    └─ IMAP :143 / STARTTLS → Dovecot

Keycloak ── SMTP :587 / STARTTLS ──> Postfix
```

- 같은 Compose 네트워크에서는 `mailserver` 같은 **서비스 이름**으로 통신합니다.
- 다른 LAN PC에 배치하면 LAN IP 또는 내부 DNS 이름을 사용합니다.
- 테스트 도메인 예시: `mail.pmart.test`, `webmail.pmart.test`, `hong@pmart.test`

## Roundcube 환경변수 예시 (불완전)

```yaml
services:
  roundcube:
    image: roundcube/roundcubemail:<검증된-버전>
    ports:
      - "8088:80"
    environment:
      ROUNDCUBEMAIL_DEFAULT_HOST: "tls://mailserver"
      ROUNDCUBEMAIL_DEFAULT_PORT: "143"
      ROUNDCUBEMAIL_SMTP_SERVER: "tls://mailserver"
      ROUNDCUBEMAIL_SMTP_PORT: "587"
```

연결 정보만 보인 예시입니다. 실제로는 Mailserver 구성, 사용자 메일함, TLS 인증서, Roundcube DB(PostgreSQL)·인증 설정이 필요하며, 이미지 버전별 환경변수 지원과 SMTP 인증 설정을 검증해야 합니다.

## TLS 인증서

- 공인 CA 인증서는 필수가 아닙니다. 자체 서명 인증서 또는 **내부 CA**를 사용합니다.
- "암호화된다"와 "클라이언트가 인증서를 신뢰한다"는 다릅니다. Roundcube·Keycloak이 메일 서버 인증서를 신뢰하도록 CA를 등록합니다.
- `mail.pmart.test` 등 실제 접속 호스트명을 인증서 SAN에 포함합니다.
- **인증서 검증을 끄는 방식은 피합니다.**
- 외부 포트 포워딩은 필요 없습니다. 필요한 포트만 LAN에 공개합니다.

## 직원 메일 계정 발급

- Keycloak 계정 생성 ≠ 메일함 생성
- Employee Service(또는 별도 프로비저닝 절차)가 **Keycloak 사용자와 메일함을 각각 생성**합니다 ([프로비저닝](../architecture/06-employee-provisioning.md)).
- Keycloak 메일 발송은 SMTP를, Roundcube 메일함 조회는 IMAP을 사용합니다.
