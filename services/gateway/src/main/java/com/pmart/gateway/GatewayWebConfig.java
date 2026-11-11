package com.pmart.gateway;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayWebConfig {

    /**
     * CSRF 토큰 발급 (D17). XSRF-TOKEN 쿠키를 설정하고 응답 본문으로 토큰을 돌려줍니다.
     * 백오피스는 Origin이 달라 Gateway 쿠키를 읽을 수 없으므로 본문의 토큰을 X-XSRF-TOKEN 헤더로 보냅니다.
     */
    @Bean
    RouterFunction<ServerResponse> csrfTokenRoute() {
        return RouterFunctions.route(GET("/csrf"), request -> {
            Mono<CsrfToken> token = request.exchange().getAttribute(CsrfToken.class.getName());
            return token == null
                    ? ServerResponse.notFound().build()
                    : token.flatMap(t -> ServerResponse.ok()
                            .bodyValue(Map.of("headerName", t.getHeaderName(), "token", t.getToken())));
        });
    }

    /**
     * 쿠키로 받은 PMart JWT를 하위 서비스에 Authorization: Bearer 헤더로 전달 (D15).
     * 하위 서비스는 헤더만 읽습니다. Keycloak(/iam/**)에는 PMart JWT를 보내지 않습니다.
     */
    @Bean
    GlobalFilter cookieToBearerRelayFilter(@Value("${pmart.jwt.cookie-name}") String cookieName) {
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            HttpCookie cookie = request.getCookies().getFirst(cookieName);
            if (cookie == null || cookie.getValue().isBlank()
                    || request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)
                    || request.getPath().value().startsWith("/iam/")) {
                return chain.filter(exchange);
            }
            var mutated = request.mutate()
                    .headers(h -> h.setBearerAuth(cookie.getValue()))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }
}
