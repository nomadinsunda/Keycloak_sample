-- PMart Refresh Token (D19)
-- 원문은 저장하지 않고 SHA-256 해시만 저장. 같은 로그인에서 이어진 토큰은 family_id로 묶어 재사용 탐지 시 한꺼번에 폐기
CREATE TABLE refresh_tokens (
    id               BIGSERIAL   PRIMARY KEY,
    token_hash       CHAR(64)    NOT NULL UNIQUE,
    family_id        UUID        NOT NULL,
    employee_no      VARCHAR(20) NOT NULL,
    keycloak_subject VARCHAR(36) NOT NULL,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,  -- 로그인 시점 + 8시간 (Rotation해도 연장 안 함)
    used_at          TIMESTAMPTZ,           -- Rotation으로 사용된 시각
    revoked_at       TIMESTAMPTZ            -- 로그아웃·재사용 탐지·퇴사로 폐기된 시각
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_employee ON refresh_tokens (employee_no);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
