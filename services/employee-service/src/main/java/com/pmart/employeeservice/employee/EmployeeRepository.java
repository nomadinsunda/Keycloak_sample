package com.pmart.employeeservice.employee;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {

    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Employee> findAll() {
        return jdbc.sql("SELECT * FROM employees ORDER BY id").query(Employee.class).list();
    }

    public Optional<Employee> findByEmployeeNo(String employeeNo) {
        return jdbc.sql("SELECT * FROM employees WHERE employee_no = :no")
                .param("no", employeeNo).query(Employee.class).optional();
    }

    public Optional<Employee> findByKeycloakUserId(String keycloakUserId) {
        return jdbc.sql("SELECT * FROM employees WHERE keycloak_user_id = :id")
                .param("id", keycloakUserId).query(Employee.class).optional();
    }

    /** 사번 기준 등록 또는 갱신. 프로비저닝 상태는 PENDING으로 둡니다. */
    public void upsertPending(String employeeNo, String firstName, String lastName, String email,
                              String department, String status, String roles) {
        jdbc.sql("""
                INSERT INTO employees (employee_no, first_name, last_name, email, department, status, roles, provisioning_status)
                VALUES (:no, :first, :last, :email, :dept, :status, :roles, 'PENDING')
                ON CONFLICT (employee_no) DO UPDATE SET
                    first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name, email = EXCLUDED.email,
                    department = EXCLUDED.department, status = EXCLUDED.status, roles = EXCLUDED.roles,
                    updated_at = now()
                """)
                .param("no", employeeNo).param("first", firstName).param("last", lastName).param("email", email)
                .param("dept", department).param("status", status).param("roles", roles)
                .update();
    }

    public void markCompleted(String employeeNo, String keycloakUserId) {
        jdbc.sql("""
                UPDATE employees SET keycloak_user_id = :kc, provisioning_status = 'COMPLETED',
                    provisioning_error = NULL, updated_at = now()
                WHERE employee_no = :no
                """).param("kc", keycloakUserId).param("no", employeeNo).update();
    }

    public void markFailed(String employeeNo, String error) {
        String message = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        jdbc.sql("""
                UPDATE employees SET provisioning_status = 'FAILED', provisioning_error = :err, updated_at = now()
                WHERE employee_no = :no
                """).param("err", message).param("no", employeeNo).update();
    }
}
