package com.pmart.employeeauth.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pmart.employeeauth.token.SigningKeyService;

/**
 * Employee Auth Service 보안 설정.
 * - 로그인 시작·Callback·JWKS는 인증 없이 접근
 * - /employee-auth/me는 Gateway가 전달한 PMart JWT(Authorization: Bearer)로 보호
 * CORS·CSRF는 Gateway에서만 처리하므로 여기서는 끕니다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/employee-auth/oauth2/start", "/employee-auth/callback",
                                "/employee-auth/logout", "/.well-known/jwks.json", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/employee-auth/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> {
                }))
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeyService keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.activeKey())));
    }

    /** 자기가 발급한 PMart JWT 검증 (공개키를 메모리에서 바로 사용) */
    @Bean
    JwtDecoder jwtDecoder(SigningKeyService keys, AuthProperties props) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.activeKey().toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwt().issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains("pmart-employee-auth")),
                new JwtClaimValidator<String>("user_type", "EMPLOYEE"::equals)));
        return decoder;
    }
}
