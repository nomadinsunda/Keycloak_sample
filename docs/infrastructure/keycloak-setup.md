# Keycloak 서버 설정과 Realm 자동 등록

## 파일별 책임

| 파일 | 책임 |
|---|---|
| `keycloak.conf` | DB, 호스트명, 포트, 프록시, 헬스 체크 등 **서버 설정** |
| `pmart-employees-realm.json` | Realm, OIDC Client, Role 등 **IAM 초기 데이터** |
| `compose.yaml` | Keycloak 컨테이너 실행, Import 파일 마운트 |
| `.env` / Secret | DB 비밀번호, Client Secret, 관리자 자격 증명 (**Git 제외**) |

## 예시 디렉터리

```text
pmart-infrastructure/
├── compose.yaml
├── .env                 # Git 제외
└── keycloak/
    ├── keycloak.conf
    └── import/
        └── pmart-employees-realm.json
```

## 최초 Import (개발용 예시)

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:<검증된-버전>
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
    volumes:
      - ./keycloak/import:/opt/keycloak/data/import:ro
    # ports 없음: Keycloak은 API Gateway 뒷단에 두며 외부에 포트를 공개하지 않음
```

- 개발용 구조 예시입니다. PostgreSQL, Secret, 호스트명, TLS 설정을 추가해야 합니다.
- 이미지 태그는 검증한 버전으로 고정합니다. `latest`를 쓰지 않습니다.

## Import 동작 주의

- `--import-realm`은 **초기 Realm 생성**에 적합합니다.
- **이미 존재하는 Realm은 Import에서 건너뛸 수 있습니다.** JSON 수정 후 재시작만으로 기존 Client 설정이 갱신된다고 가정하지 않습니다.
- 이후 변경은 Admin REST API 또는 관리 CLI(`kcadm.sh`)로 관리합니다.
- JSON 안의 Client Secret 치환 방식은 사용 중인 Keycloak 버전의 지원 여부와 실제 Import 결과로 검증합니다.

## DB

- 이 프로젝트는 데이터베이스로 **PostgreSQL만 사용**합니다. Keycloak 개발 모드의 기본값인 H2는 쓰지 않습니다.
- 기존 PostgreSQL 인스턴스에 Keycloak 전용 DB(예: `keycloak`)와 전용 계정을 만들어도 됩니다.
- PMart 서비스가 Keycloak DB에 직접 접근하지 않습니다.

## 프록시·호스트 설정

Keycloak은 API Gateway 뒷단(`/iam/**`)에 두므로 `hostname`, `http-relative-path`, 프록시 헤더 설정을 Gateway와 맞춰야 합니다. 자세한 내용은 [Gateway 라우팅](../architecture/05-gateway-routing.md)을 참고하세요.

## SMTP 연결

Keycloak Realm 설정의 Email 항목에서 LAN 메일 서버(Postfix, `:587` STARTTLS)를 SMTP 서버로 지정합니다. 자세한 내용은 [LAN 메일](lan-mail.md)을 참고하세요.
