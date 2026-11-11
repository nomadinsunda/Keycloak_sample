#!/bin/sh
# PostgreSQL 컨테이너 최초 기동 시 1회 실행됩니다.
# 구성 요소별로 DB와 전용 계정을 만들고, 각 계정은 자기 DB만 소유합니다.
set -eu

create_db() {
  db="$1"; user="$2"; password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
	CREATE ROLE "$user" LOGIN PASSWORD '$password';
	CREATE DATABASE "$db" OWNER "$user";
	REVOKE ALL ON DATABASE "$db" FROM PUBLIC;
SQL
  echo "created database $db (owner $user)"
}

create_db keycloak      keycloak      "$KEYCLOAK_DB_PASSWORD"
create_db employee      employee      "$EMPLOYEE_DB_PASSWORD"
create_db employee_auth employee_auth "$EMPLOYEE_AUTH_DB_PASSWORD"
