package com.pmart.employeeauth.bootstrap;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.pmart.employeeauth.config.AuthProperties;
import com.pmart.employeeauth.employee.EmployeeDirectoryClient;

/**
 * 테스트용 인사팀 백오피스 계정 생성 (D12).
 * 부팅 시 Keycloak 사용자·비밀번호를 만들고 Employee Service에 직원 정보(HR_ADMIN)를 등록해,
 * 부팅 직후 바로 백오피스에 로그인할 수 있게 합니다.
 * 이미 있으면 다시 만들지 않고 비밀번호도 덮어쓰지 않습니다.
 */
@Component
public class HrTestAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HrTestAccountBootstrap.class);
    private static final int MAX_ATTEMPTS = 30;

    private final AuthProperties props;
    private final EmployeeDirectoryClient employees;
    private final RestClient rest = RestClient.create();

    public HrTestAccountBootstrap(AuthProperties props, EmployeeDirectoryClient employees) {
        this.props = props;
        this.employees = employees;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                ensureAccount();
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("테스트용 인사팀 계정 생성 실패", e);
                }
                log.warn("테스트용 인사팀 계정 생성 재시도 {}/{}: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                Thread.sleep(5000);
            }
        }
    }

    private void ensureAccount() {
        String employeeNo = props.hrTestAccount().employeeNo();
        // IAM 계정 ID = Keycloak 로그인 ID이자 이메일 (D21). Keycloak은 username을 소문자로 저장
        String iamId = props.hrTestAccount().iamId().toLowerCase(Locale.ROOT);
        String token = bootstrapToken();

        String userId = findUserId(token, iamId);
        if (userId == null) {
            URI location = rest.post()
                    .uri(adminUrl("/users"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", iamId, "firstName", "인사", "lastName", "테스트",
                            "email", iamId, "emailVerified", true, "enabled", true))
                    .retrieve().toBodilessEntity().getHeaders().getLocation();
            String path = location.getPath();
            userId = path.substring(path.lastIndexOf('/') + 1);
            // 테스트용 계정이므로 첫 로그인 때 비밀번호 변경을 강제하지 않음 (temporary=false)
            rest.put()
                    .uri(adminUrl("/users/" + userId + "/reset-password"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", props.hrTestAccount().password(), "temporary", false))
                    .retrieve().toBodilessEntity();
            log.info("테스트용 인사팀 IAM 계정 생성: {}", iamId);
            disableLegacyAccount(token, employeeNo, userId);
        } else {
            log.info("테스트용 인사팀 IAM 계정이 이미 있습니다: {}", iamId);
        }

        employees.upsert(employeeNo, iamId, "인사", "테스트", "인사팀", List.of("EMPLOYEE", "HR_ADMIN"), userId);
        log.info("테스트용 인사팀 계정 준비 완료: {} → {} (HR_ADMIN)", employeeNo, iamId);
    }

    /** 사번을 로그인 ID로 쓰던 이전 방식의 계정이 남아 있으면 비활성화 (D21 이전 데이터 정리) */
    private void disableLegacyAccount(String token, String employeeNo, String newUserId) {
        String legacyId = findUserId(token, employeeNo.toLowerCase(Locale.ROOT));
        if (legacyId != null && !legacyId.equals(newUserId)) {
            rest.put()
                    .uri(adminUrl("/users/" + legacyId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", false))
                    .retrieve().toBodilessEntity();
            log.info("이전 방식(사번 로그인) 계정 비활성화: {}", employeeNo);
        }
    }

    private String findUserId(String token, String username) {
        List<Map<String, Object>> users = rest.get()
                .uri(adminUrl("/users?exact=true&username={u}"), username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return users == null || users.isEmpty() ? null : (String) users.get(0).get("id");
    }

    /** pmart-employee-auth-bootstrap Client의 Client Credentials 토큰 (Gateway 경유 안 함) */
    private String bootstrapToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> body = rest.post()
                .uri(props.keycloakInternalRealmUrl() + "/protocol/openid-connect/token")
                .headers(h -> h.setBasicAuth(props.keycloak().bootstrapClientId(), props.keycloak().bootstrapClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return (String) body.get("access_token");
    }

    private String adminUrl(String path) {
        return props.keycloak().internalBaseUrl() + "/admin/realms/" + props.keycloak().realm() + path;
    }
}
