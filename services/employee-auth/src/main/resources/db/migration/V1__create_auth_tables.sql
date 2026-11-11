-- 로그인 트랜잭션: 로그인 시작 ~ Callback 사이에 state·nonce·PKCE code_verifier를 잠깐 보관
CREATE TABLE login_transactions (
    state         VARCHAR(64)  PRIMARY KEY,
    nonce         VARCHAR(64)  NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_login_transactions_expires_at ON login_transactions (expires_at);

-- PMart JWT 서명키 (RS256). 테스트 프로젝트용으로 DB에 보관. 운영에서는 KMS·Vault 등 별도 키 저장소 사용
CREATE TABLE signing_keys (
    kid         VARCHAR(64) PRIMARY KEY,
    private_key TEXT        NOT NULL,   -- PKCS#8, Base64
    public_key  TEXT        NOT NULL,   -- X.509, Base64
    active      BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
