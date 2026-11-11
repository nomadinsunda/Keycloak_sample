package com.pmart.employeeservice.employee;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
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

    /** 퇴사·비활성화. 자기 자신은 비활성화할 수 없음 */
    @PostMapping("/{employeeNo}/deactivate")
    public EmployeeResponse deactivate(@PathVariable String employeeNo, @AuthenticationPrincipal Jwt jwt) {
        if (employeeNo.equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신은 비활성화할 수 없습니다.");
        }
        return EmployeeResponse.from(provisioning.deactivate(employeeNo));
    }

    public record RegisterEmployeeRequest(
            // 사번은 인사 정보 (D21). 영문 대문자·숫자 3~20자
            @NotBlank(message = "사번을 입력하세요.")
            @Pattern(regexp = "^[A-Z0-9]{3,20}$", message = "사번은 영문 대문자·숫자 3~20자로 입력하세요. (예: EMP0003)") String employeeNo,
            // IAM 계정명 (D21). IAM 계정 ID = 계정명@도메인 (예: gildong.hong@pmart.test)
            @NotBlank(message = "IAM 계정명을 입력하세요.")
            @Size(min = 3, max = 30, message = "IAM 계정명은 3~30자로 입력하세요.")
            @Pattern(regexp = "^[a-z0-9]+([._-][a-z0-9]+)*$",
                    message = "IAM 계정명은 영문 소문자·숫자와 . _ - 만 사용하세요. (예: gildong.hong)") String iamAccount,
            @NotBlank(message = "이름을 입력하세요.") @Size(max = 50, message = "이름은 50자 이하로 입력하세요.") String firstName,
            @NotBlank(message = "성을 입력하세요.") @Size(max = 50, message = "성은 50자 이하로 입력하세요.") String lastName,
            @Size(max = 100, message = "부서는 100자 이하로 입력하세요.") String department,
            @NotBlank(message = "초기 비밀번호를 입력하세요.")
            @Size(min = 8, max = 64, message = "초기 비밀번호는 8~64자로 입력하세요.") String initialPassword,
            boolean hrAdmin) {
    }

    public record EmployeeResponse(String employeeNo, String iamId, String firstName, String lastName, String email,
                                   String department, String status, List<String> roles,
                                   String provisioningStatus, String provisioningError) {
        static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(e.employeeNo(), e.iamId(), e.firstName(), e.lastName(), e.email(), e.department(),
                    e.status(), e.roleList(), e.provisioningStatus(), e.provisioningError());
        }
    }
}
