package com.pmart.employeeauth.employee;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.pmart.employeeauth.config.AuthProperties;

/**
 * Employee Service 내부 API 클라이언트 (내부 네트워크 직접 호출, 내부 API 키 사용).
 */
@Component
public class EmployeeDirectoryClient {

    private final RestClient rest;

    public EmployeeDirectoryClient(AuthProperties props) {
        this.rest = RestClient.builder()
                .baseUrl(props.employeeService().baseUrl())
                .defaultHeader("X-Internal-Api-Key", props.employeeService().internalApiKey())
                .build();
    }

    public Optional<EmployeeStatus> findByKeycloakId(String keycloakUserId) {
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/internal/employees/by-keycloak-id/{id}", keycloakUserId)
                    .retrieve()
                    .body(EmployeeStatus.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public void upsert(String employeeNo, String iamId, String firstName, String lastName, String department,
                       List<String> roles, String keycloakUserId) {
        rest.put()
                .uri("/internal/employees/{no}", employeeNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "iamId", iamId, "firstName", firstName, "lastName", lastName, "department", department,
                        "status", "ACTIVE", "roles", roles, "keycloakUserId", keycloakUserId))
                .retrieve()
                .toBodilessEntity();
    }

    public record EmployeeStatus(String employeeNo, String iamId, String firstName, String lastName, String status,
                                 List<String> roles) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
