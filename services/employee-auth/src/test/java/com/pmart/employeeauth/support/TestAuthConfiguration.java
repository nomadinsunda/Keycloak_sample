package com.pmart.employeeauth.support;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pmart.employeeauth.config.AuthProperties;
import com.pmart.employeeauth.token.SigningKeyService;

/**
 * 테스트용 설정값과 JwtEncoder.
 * 값은 src/main/resources/application.yml과 같게 둡니다(15분, 8시간, 쿠키 이름, aud 3개).
 * 비밀 값(Client Secret 등)은 이 테스트 범위에서 쓰지 않으므로 더미 문자열입니다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAuthConfiguration {

    public static final String ISSUER = "https://employee-auth.pmart.test";
    public static final List<String> AUDIENCES = List.of("pmart-employee-auth", "pmart-employee-service", "pmart-sample-api");

    @Bean
    AuthProperties authProperties() {
        return new AuthProperties(
                "http://localhost:8080",
                "http://localhost:3000",
                new AuthProperties.Jwt(ISSUER, AUDIENCES, Duration.ofMinutes(15), "PMART_EMP_AT",
                        Duration.ofHours(8), "PMART_EMP_RT"),
                Duration.ofMinutes(5),
                new AuthProperties.Keycloak("http://keycloak:8080/iam", "pmart-employees", "pmart-employee-auth",
                        "unused", "pmart-employee-auth-bootstrap", "unused"),
                new AuthProperties.EmployeeService("http://employee-service:8080", "unused"),
                new AuthProperties.HrTestAccount("HR000001", "hr.admin@pmart.test", "unused"),
                new AuthProperties.Mobile("pmart-employee://login-callback", Duration.ofSeconds(60)));
    }

    /** 운영 SecurityConfig#jwtEncoder와 같은 방식. SecurityConfig 전체(필터 체인)는 이 슬라이스에 올리지 않으므로 따로 정의 */
    @Bean
    JwtEncoder jwtEncoder(SigningKeyService keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.activeKey())));
    }
}
