package com.pmart.employeeservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pmart")
public record PmartProperties(Jwt jwt, Keycloak keycloak, Internal internal, EmployeeAuth employeeAuth, Iam iam) {

    /** IAM 계정 도메인. IAM 계정 ID = 계정명@domain (D21) */
    public record Iam(String domain) {
    }

    public record EmployeeAuth(String baseUrl) {
    }

    public record Jwt(String issuer, String jwkSetUri, String audience) {
    }

    public record Keycloak(String baseUrl, String realm, String clientId, String clientSecret) {
    }

    public record Internal(String apiKey) {
    }
}
