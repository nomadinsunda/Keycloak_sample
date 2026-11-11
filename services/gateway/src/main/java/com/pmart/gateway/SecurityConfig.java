package com.pmart.gateway;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;

import reactor.core.publisher.Mono;

/**
 * Gateway 보안 설정 (reactive Spring Security, Netty).
 * - PMart JWT 검증: Authorization 헤더(Flutter) 또는 쿠키(인사팀 백오피스)  (D15)
 * - CORS: 백오피스 Origin만 허용, 쿠키 전송 허용  (D16)
 * - CSRF: 쿠키로 인증하는 상태 변경 요청에 Double Submit Cookie 검사  (D17)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** PMart JWT 없이 접근할 수 있는 경로 */
    static final List<String> PUBLIC_PATHS = List.of(
            "/iam/**",
            "/employee-auth/oauth2/start",
            "/employee-auth/callback",
            "/employee-auth/logout",
            "/csrf",
            "/actuator/health");

    private static final Set<HttpMethod> STATE_CHANGING =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final String backofficeOrigin;
    private final String cookieName;

    public SecurityConfig(@Value("${pmart.backoffice-origin}") String backofficeOrigin,
                          @Value("${pmart.jwt.cookie-name}") String cookieName) {
        this.backofficeOrigin = backofficeOrigin;
        this.cookieName = cookieName;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        CookieServerCsrfTokenRepository csrfRepository = new CookieServerCsrfTokenRepository();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(c -> c.sameSite("Lax"));

        return http
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .requireCsrfProtectionMatcher(csrfRequiredMatcher()))
                .addFilterBefore(originCheckFilter(), SecurityWebFiltersOrder.CSRF)
                .authorizeExchange(auth -> auth
                        // Keycloak Admin REST API·관리 콘솔은 외부에 노출하지 않음
                        .pathMatchers("/iam/admin/**").denyAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                        .pathMatchers("/employee-auth/**", "/employee-service/**", "/sample-api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenConverter(headerOrCookieTokenConverter())
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
                .build();
    }

    /** PMart JWT 검증: 서명(JWKS 내부 직접 호출), iss, exp, user_type. aud·역할은 각 하위 서비스가 검사 */
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${pmart.jwt.jwk-set-uri}") String jwkSetUri,
                                  @Value("${pmart.jwt.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>("user_type", "EMPLOYEE"::equals)));
        return decoder;
    }

    /** Authorization 헤더를 먼저 보고, 없으면 쿠키에서 JWT를 읽음. 공개 경로에서는 읽지 않음(만료 쿠키로 로그인이 막히지 않게) */
    private ServerAuthenticationConverter headerOrCookieTokenConverter() {
        return exchange -> {
            String path = exchange.getRequest().getPath().value();
            if (isPublic(path)) {
                return Mono.empty();
            }
            String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Mono.just(new BearerTokenAuthenticationToken(header.substring(7).trim()));
            }
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst(cookieName);
            if (cookie != null && !cookie.getValue().isBlank()) {
                return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
            }
            return Mono.empty();
        };
    }

    /**
     * CSRF 검사 대상: 쿠키로 인증하는 상태 변경 요청.
     * 제외: Bearer 헤더 요청(Flutter), /iam/**(Keycloak 자체 CSRF 방어), Callback(GET)
     */
    private ServerWebExchangeMatcher csrfRequiredMatcher() {
        return exchange -> {
            var request = exchange.getRequest();
            boolean required = STATE_CHANGING.contains(request.getMethod())
                    && !request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)
                    && !PATHS.match("/iam/**", request.getPath().value());
            return required ? MatchResult.match() : MatchResult.notMatch();
        };
    }

    /** 보조 방어: 쿠키 인증 상태 변경 요청의 Origin이 백오피스가 아니면 거부 */
    private WebFilter originCheckFilter() {
        return (exchange, chain) -> csrfRequiredMatcher().matches(exchange).flatMap(result -> {
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (result.isMatch() && origin != null && !origin.equals(backofficeOrigin)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        });
    }

    /** CORS: 백오피스 Origin만 허용, 쿠키 전송 허용. 하위 서비스는 CORS 헤더를 붙이지 않음 */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(backofficeOrigin));
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/employee-auth/**", config);
        source.registerCorsConfiguration("/employee-service/**", config);
        source.registerCorsConfiguration("/sample-api/**", config);
        source.registerCorsConfiguration("/csrf", config);
        return source;
    }

    static boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p -> PATHS.match(p, path));
    }
}
