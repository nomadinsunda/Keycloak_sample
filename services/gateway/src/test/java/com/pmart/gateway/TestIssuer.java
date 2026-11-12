package com.pmart.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 토큰 발급자. 자기 RSA 키로 서명하고, JDK 내장 HTTP 서버로 그 공개키(JWKS)를 제공합니다.
 *
 * <p>CLAUDE.md: Customer Auth·Rider Auth는 이 저장소에서 구현하지 않으므로, 교차 접근 거부 테스트에는
 * 실제 서비스 대신 이 클래스로 만든 "테스트용 키로 서명한 고객·라이더 토큰"을 씁니다.
 * 같은 방식으로 Employee Auth Service 대역(PMart 직원 토큰)도 만듭니다.
 *
 * <p>sample-api 테스트의 같은 이름 클래스와 내용이 같습니다(서비스마다 독립된 Gradle 프로젝트라 복사해 둠).
 */
final class TestIssuer implements AutoCloseable {

    final String issuer;
    final RSAKey key;
    /** JWKS 요청 횟수. 허용되지 않은 발급자의 JWKS를 따라가지 않는지 확인용 */
    final AtomicInteger jwksRequests = new AtomicInteger();
    private final HttpServer server;

    TestIssuer(String issuer) {
        this.issuer = issuer;
        try {
            this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/.well-known/jwks.json", exchange -> {
            jwksRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        server.start();
    }

    String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** PMart 직원 토큰 형식의 기본 클레임 (docs/architecture/04-tokens-and-jwt.md) */
    JWTClaimsSet.Builder employeeClaims(String employeeNo, List<String> roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(employeeNo)
                .audience(List.of("pmart-employee-auth", "pmart-employee-service", "pmart-sample-api"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID(UUID.randomUUID().toString())
                .claim("user_type", "EMPLOYEE")
                .claim("roles", roles)
                .claim("identity_provider", "keycloak")
                .claim("external_subject", UUID.randomUUID().toString());
    }

    /** 고객·라이더 토큰 형식: user_type만 다르고, 자기 발급자 키로 서명 */
    JWTClaimsSet.Builder otherUserClaims(String userType, String audience) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("U" + UUID.randomUUID())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("user_type", userType)
                .claim("roles", List.of(userType));
    }

    String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    /** 다른 키로 서명(위조). kid는 지정한 키의 것 */
    static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
