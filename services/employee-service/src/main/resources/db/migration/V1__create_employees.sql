-- PMart 직원 인사 정보와 Keycloak 계정 매핑
CREATE TABLE employees (
    id                  BIGSERIAL PRIMARY KEY,
    employee_no         VARCHAR(20)  NOT NULL UNIQUE,  -- 사번 (Keycloak username과 같음)
    first_name          VARCHAR(50)  NOT NULL,
    last_name           VARCHAR(50)  NOT NULL,
    email               VARCHAR(200) NOT NULL,
    department          VARCHAR(100),
    status              VARCHAR(20)  NOT NULL,         -- ACTIVE / INACTIVE
    roles               VARCHAR(200) NOT NULL,         -- 쉼표 구분 (예: EMPLOYEE,HR_ADMIN)
    keycloak_user_id    VARCHAR(36)  UNIQUE,           -- Keycloak sub
    provisioning_status VARCHAR(20)  NOT NULL,         -- PENDING / COMPLETED / FAILED
    provisioning_error  VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
