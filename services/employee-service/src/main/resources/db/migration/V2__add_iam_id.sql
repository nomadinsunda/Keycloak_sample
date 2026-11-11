-- IAM 계정 ID (D21): Keycloak이 발급하는 직원 계정. 예) gildong.hong@pmart.test
-- Keycloak 로그인 ID(username)이자 이메일. 사번(employee_no)은 인사 정보로만 사용
ALTER TABLE employees ADD COLUMN iam_id VARCHAR(100) UNIQUE;
