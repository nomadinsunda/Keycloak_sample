package com.pmart.employeeservice.employee;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 API (Employee Auth Service 전용).
 * Gateway 라우트가 없으므로 외부에서 접근할 수 없고, 내부 API 키로 보호합니다.
 */
@RestController
@RequestMapping("/internal/employees")
public class InternalEmployeeController {

    private final EmployeeRepository repository;

    public InternalEmployeeController(EmployeeRepository repository) {
        this.repository = repository;
    }

    /** 로그인 시 Employee Auth Service가 Keycloak sub로 직원 상태·역할을 확인 */
    @GetMapping("/by-keycloak-id/{keycloakUserId}")
    public ResponseEntity<EmployeeStatus> byKeycloakId(@PathVariable String keycloakUserId) {
        return repository.findByKeycloakUserId(keycloakUserId)
                .map(e -> ResponseEntity.ok(new EmployeeStatus(e.employeeNo(), e.firstName(), e.lastName(),
                        e.status(), e.roleList())))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 부팅 시 Employee Auth Service가 테스트용 인사팀 계정의 직원 정보를 등록 */
    @PutMapping("/{employeeNo}")
    public EmployeeStatus upsert(@PathVariable String employeeNo, @Valid @RequestBody UpsertRequest req) {
        repository.upsertPending(employeeNo, req.firstName(), req.lastName(), req.email(), req.department(),
                req.status(), String.join(",", req.roles()));
        repository.markCompleted(employeeNo, req.keycloakUserId());
        return new EmployeeStatus(employeeNo, req.firstName(), req.lastName(), req.status(), req.roles());
    }

    public record EmployeeStatus(String employeeNo, String firstName, String lastName, String status,
                                 List<String> roles) {
    }

    public record UpsertRequest(@NotBlank String firstName, @NotBlank String lastName, @NotBlank String email,
                                String department, @NotBlank String status, @NotEmpty List<String> roles,
                                @NotBlank String keycloakUserId) {
    }
}
