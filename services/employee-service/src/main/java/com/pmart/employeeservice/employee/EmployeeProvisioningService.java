package com.pmart.employeeservice.employee;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.pmart.employeeservice.keycloak.KeycloakAdminClient;

/**
 * 직원 등록 → Keycloak 계정 발급.
 * 직원 DB와 Keycloak은 하나의 트랜잭션으로 묶을 수 없으므로 발급 상태(PENDING/COMPLETED/FAILED)를 기록하고,
 * 같은 사번으로 다시 요청하면 이어서 처리합니다(멱등).
 */
@Service
public class EmployeeProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeProvisioningService.class);

    private final EmployeeRepository repository;
    private final KeycloakAdminClient keycloak;
    private final EmployeeAuthSessionClient sessions;
    private final String iamDomain;

    public EmployeeProvisioningService(EmployeeRepository repository, KeycloakAdminClient keycloak,
                                       EmployeeAuthSessionClient sessions,
                                       com.pmart.employeeservice.config.PmartProperties props) {
        this.iamDomain = props.iam().domain();
        this.repository = repository;
        this.keycloak = keycloak;
        this.sessions = sessions;
    }

    public Employee register(EmployeeController.RegisterEmployeeRequest req) {
        repository.findByEmployeeNo(req.employeeNo()).ifPresent(e -> {
            if ("COMPLETED".equals(e.provisioningStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 사번입니다: " + req.employeeNo());
            }
        });

        // IAM 계정 ID = 인사팀이 정한 계정명 + @도메인. Keycloak 로그인 ID이자 이메일 (D21)
        String iamId = req.iamAccount().toLowerCase(Locale.ROOT) + "@" + iamDomain;
        repository.findByIamId(iamId).ifPresent(other -> {
            if (!other.employeeNo().equals(req.employeeNo())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 IAM 계정입니다: " + iamId);
            }
        });
        String roles = req.hrAdmin() ? "EMPLOYEE,HR_ADMIN" : "EMPLOYEE";
        repository.upsertPending(req.employeeNo(), iamId, req.firstName(), req.lastName(),
                req.department(), "ACTIVE", roles);

        try {
            String keycloakUserId = keycloak.ensureUser(iamId, req.firstName(), req.lastName(), iamId);
            // Keycloak에 같은 IAM 계정이 이미 있는데 다른 직원에게 연결돼 있으면 거부 (재시도로 이어 만드는 경우만 허용)
            repository.findByKeycloakUserId(keycloakUserId).ifPresent(other -> {
                if (!other.employeeNo().equals(req.employeeNo())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 IAM 계정입니다: " + iamId);
                }
            });
            // 인사팀이 정한 초기 비밀번호. 첫 로그인 때 직원이 직접 바꾸도록 temporary=true
            keycloak.setPassword(keycloakUserId, req.initialPassword(), true);
            repository.markCompleted(req.employeeNo(), keycloakUserId);
            log.info("IAM 계정 발급 완료: {} → {} (keycloak={})", req.employeeNo(), iamId, keycloakUserId);
        } catch (ResponseStatusException e) {
            repository.markFailed(req.employeeNo(), e.getReason());
            throw e;
        } catch (RuntimeException e) {
            repository.markFailed(req.employeeNo(), e.getMessage());
            log.warn("직원 계정 발급 실패: {}", req.employeeNo(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak 계정 발급 실패. 같은 사번으로 다시 시도하세요.");
        }
        return repository.findByEmployeeNo(req.employeeNo()).orElseThrow();
    }

    /**
     * 퇴사·비활성화 (D19).
     * 1) 직원 DB 상태 INACTIVE  → 이후 토큰 갱신·로그인 거부
     * 2) Keycloak 계정 비활성화 + SSO 세션 종료 → Keycloak 로그인 자체 불가
     * 3) Employee Auth Service에 PMart Refresh Token 전부 폐기 요청
     * 이미 발급된 Access Token은 남은 수명(최대 15분) 동안 유효합니다.
     */
    public Employee deactivate(String employeeNo) {
        Employee employee = repository.findByEmployeeNo(employeeNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 사번입니다: " + employeeNo));
        repository.updateStatus(employeeNo, "INACTIVE");
        try {
            if (employee.keycloakUserId() != null) {
                keycloak.disableAndLogout(employee.keycloakUserId());
            }
            sessions.revokeAll(employeeNo);
        } catch (RuntimeException e) {
            log.warn("직원 비활성화 후속 처리 실패: {}", employeeNo, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "직원 상태는 INACTIVE로 바뀌었지만 Keycloak·세션 정리에 실패했습니다. 다시 시도하세요.");
        }
        log.info("직원 비활성화 완료: {}", employeeNo);
        return repository.findByEmployeeNo(employeeNo).orElseThrow();
    }
}
