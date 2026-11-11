-- Flutter 앱 로그인 (D20)
-- 로그인 트랜잭션에 클라이언트 종류와 앱의 app_challenge(= BASE64URL(SHA256(app_verifier)))를 함께 저장
ALTER TABLE login_transactions
    ADD COLUMN client        VARCHAR(20) NOT NULL DEFAULT 'backoffice',  -- backoffice / mobile
    ADD COLUMN app_challenge VARCHAR(64);

-- 일회성 ticket: Callback 후 앱으로 돌아갈 때 URL에 싣는 PMart 내부 코드 (OAuth Authorization Code와 다름)
-- 원문은 저장하지 않고 해시만 저장. 단기 만료, 단일 사용, 로그인을 시작한 앱의 app_verifier가 있어야 교환 가능
CREATE TABLE mobile_login_tickets (
    ticket_hash      CHAR(64)    PRIMARY KEY,
    app_challenge    VARCHAR(64) NOT NULL,
    employee_no      VARCHAR(20) NOT NULL,
    keycloak_subject VARCHAR(36) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL
);
