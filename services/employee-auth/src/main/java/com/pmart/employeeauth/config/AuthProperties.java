package com.pmart.employeeauth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pmart")
public record AuthProperties(
        String publicBaseUrl,
        String backofficeOrigin,
        Jwt jwt,
        Duration loginTransactionTtl,
        Keycloak keycloak,
        EmployeeService employeeService,
        HrTestAccount hrTestAccount,
        Mobile mobile) {

    /** Flutter 앱: 로그인 후 돌아갈 커스텀 스킴 주소, 일회성 ticket 수명 */
    public record Mobile(String redirectUri, Duration ticketTtl) {
    }

    public record Jwt(String issuer, List<String> audiences, Duration accessTokenTtl, String cookieName,
                      Duration refreshTokenTtl, String refreshCookieName) {
    }

    public record Keycloak(String internalBaseUrl, String realm, String clientId, String clientSecret,
                           String bootstrapClientId, String bootstrapClientSecret) {
    }

    public record EmployeeService(String baseUrl, String internalApiKey) {
    }

    /** 테스트용 인사팀 계정: 사번(인사 정보), IAM 계정 ID(로그인 ID), 비밀번호 */
    public record HrTestAccount(String employeeNo, String iamId, String password) {
    }

    /** 브라우저가 보는 Keycloak Realm 주소 = ID Token의 iss */
    public String keycloakIssuer() {
        return publicBaseUrl + "/iam/realms/" + keycloak.realm();
    }

    /** 서버 간 호출용 Keycloak Realm 주소 (Gateway 경유 안 함) */
    public String keycloakInternalRealmUrl() {
        return keycloak.internalBaseUrl() + "/realms/" + keycloak.realm();
    }

    public String callbackUrl() {
        return publicBaseUrl + "/employee-auth/callback";
    }

    public boolean secureCookie() {
        return publicBaseUrl.startsWith("https://");
    }
}
