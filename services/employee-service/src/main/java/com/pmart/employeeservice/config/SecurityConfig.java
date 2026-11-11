package com.pmart.employeeservice.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Employee Service 보안 설정.
 * - /internal/** : 서비스 간 내부 API. Gateway 라우트가 없으며 내부 API 키로 보호
 * - /employee-service/** : Gateway가 전달한 PMart JWT(Authorization: Bearer)로 보호
 * CORS·CSRF는 Gateway에서만 처리하므로 여기서는 끕니다.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(PmartProperties.class)
public class SecurityConfig {

    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Bean
    @Order(1)
    SecurityFilterChain internalApiChain(HttpSecurity http, PmartProperties props) throws Exception {
        return http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new InternalApiKeyFilter(props.internal().apiKey()), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/employee-service/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /** PMart JWT 검증: 서명(JWKS), iss, exp, aud, user_type */
    @Bean
    JwtDecoder jwtDecoder(PmartProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwt().jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwt().issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(props.jwt().audience())),
                new JwtClaimValidator<String>("user_type", "EMPLOYEE"::equals));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** roles 클레임 → ROLE_ 권한 (hasRole("HR_ADMIN")으로 검사) */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** 내부 API 키 검사. 테스트 프로젝트용 단순 방식 */
    static class InternalApiKeyFilter extends OncePerRequestFilter {
        private final byte[] expected;

        InternalApiKeyFilter(String apiKey) {
            this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, java.io.IOException {
            String given = request.getHeader(INTERNAL_API_KEY_HEADER);
            if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
