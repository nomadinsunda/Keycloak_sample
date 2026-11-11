package com.pmart.employeeservice.keycloak;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.pmart.employeeservice.config.PmartProperties;

/**
 * Keycloak Admin REST API 클라이언트.
 * Gateway를 경유하지 않고 내부 네트워크에서 Keycloak을 직접 호출합니다.
 * 인증은 pmart-employee-service Client의 Client Credentials 토큰을 사용합니다.
 */
@Component
public class KeycloakAdminClient {

    private final PmartProperties.Keycloak kc;
    private final RestClient rest = RestClient.create();

    public KeycloakAdminClient(PmartProperties props) {
        this.kc = props.keycloak();
    }

    /** username(사번)으로 사용자를 찾고, 없으면 만듭니다. 반환값은 Keycloak 사용자 ID(sub). */
    public String ensureUser(String username, String firstName, String lastName, String email) {
        String token = serviceAccountToken();
        Optional<String> existing = findUserId(token, username);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            URI location = rest.post()
                    .uri(adminUrl("/users"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "firstName", firstName,
                            "lastName", lastName,
                            "email", email,
                            "emailVerified", true,
                            "enabled", true))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders().getLocation();
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (HttpClientErrorException.Conflict e) {
            // 동시에 생성된 경우: 다시 조회
            return findUserId(token, username).orElseThrow(() -> e);
        }
    }

    /** 비밀번호 설정. temporary=true면 첫 로그인 때 비밀번호 변경을 요구합니다. */
    public void setPassword(String userId, String password, boolean temporary) {
        rest.put()
                .uri(adminUrl("/users/" + userId + "/reset-password"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceAccountToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", temporary))
                .retrieve()
                .toBodilessEntity();
    }

    /** 퇴사·비활성화: Keycloak 계정을 끄고(로그인 불가) SSO 세션을 모두 종료 */
    public void disableAndLogout(String userId) {
        String token = serviceAccountToken();
        rest.put()
                .uri(adminUrl("/users/" + userId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false))
                .retrieve()
                .toBodilessEntity();
        rest.post()
                .uri(adminUrl("/users/" + userId + "/logout"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private Optional<String> findUserId(String token, String username) {
        List<Map<String, Object>> users = rest.get()
                .uri(adminUrl("/users?exact=true&username={u}"), username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return users == null || users.isEmpty() ? Optional.empty() : Optional.of((String) users.get(0).get("id"));
    }

    private String serviceAccountToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> body = rest.post()
                .uri(kc.baseUrl() + "/realms/" + kc.realm() + "/protocol/openid-connect/token")
                .headers(h -> h.setBasicAuth(kc.clientId(), kc.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return (String) body.get("access_token");
    }

    private String adminUrl(String path) {
        return kc.baseUrl() + "/admin/realms/" + kc.realm() + path;
    }
}
