package com.pmart.employeeservice.employee;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인사팀 백오피스용 직원 API (Gateway 경유, PMart JWT 필요, HR_ADMIN 역할만 허용).
 */
@RestController
@RequestMapping("/employee-service/employees")
@PreAuthorize("hasRole('HR_ADMIN')")
public class EmployeeController {

    private final EmployeeProvisioningService provisioning;
    private final EmployeeRepository repository;

    public EmployeeController(EmployeeProvisioningService provisioning, EmployeeRepository repository) {
        this.provisioning = provisioning;
        this.repository = repository;
    }

    @GetMapping
    public List<EmployeeResponse> list() {
        return repository.findAll().stream().map(EmployeeResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse register(@Valid @RequestBody RegisterEmployeeRequest request) {
        return EmployeeResponse.from(provisioning.register(request));
    }

    public record RegisterEmployeeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{2,3}\\d{6}$", message = "사번 형식: 예) EMP000123") String employeeNo,
            @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Size(max = 50) String lastName,
            @Size(max = 100) String department,
            @NotBlank @Size(min = 8, max = 64) String initialPassword,
            boolean hrAdmin) {
    }

    public record EmployeeResponse(String employeeNo, String firstName, String lastName, String email,
                                   String department, String status, List<String> roles,
                                   String provisioningStatus, String provisioningError) {
        static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(e.employeeNo(), e.firstName(), e.lastName(), e.email(), e.department(),
                    e.status(), e.roleList(), e.provisioningStatus(), e.provisioningError());
        }
    }
}
