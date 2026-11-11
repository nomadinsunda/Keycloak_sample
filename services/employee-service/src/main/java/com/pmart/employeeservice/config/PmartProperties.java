package com.pmart.employeeservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pmart")
public record PmartProperties(Jwt jwt, Keycloak keycloak, Internal internal, EmployeeAuth employeeAuth) {

    public record EmployeeAuth(String baseUrl) {
    }

    public record Jwt(String issuer, String jwkSetUri, String audience) {
    }

    public record Keycloak(String baseUrl, String realm, String clientId, String clientSecret) {
    }

    public record Internal(String apiKey) {
    }
}
