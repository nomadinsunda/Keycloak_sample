package com.pmart.employeeauth.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.pmart.employeeauth.config.AuthProperties;

/**
 * PMart Employee Access Token 발급 (D14).
 * RS256, 수명 15분, 개인정보 미포함.
 */
@Service
public class PmartTokenService {

    private final JwtEncoder encoder;
    private final SigningKeyService keys;
    private final AuthProperties props;

    public PmartTokenService(JwtEncoder encoder, SigningKeyService keys, AuthProperties props) {
        this.encoder = encoder;
        this.keys = keys;
        this.props = props;
    }

    public IssuedToken issue(String employeeNo, List<String> roles, String keycloakSubject) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.jwt().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .subject(employeeNo)
                .audience(props.jwt().audiences())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("user_type", "EMPLOYEE")
                .claim("roles", roles)
                .claim("identity_provider", "keycloak")
                .claim("external_subject", keycloakSubject)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .keyId(keys.activeKey().getKeyID())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** 인사팀 백오피스로 JWT를 전달하는 쿠키 (D15) */
    public ResponseCookie accessTokenCookie(String token) {
        return ResponseCookie.from(props.jwt().cookieName(), token)
                .httpOnly(true)
                .secure(props.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(props.jwt().accessTokenTtl())
                .build();
    }

    /** 로그아웃: 같은 이름의 쿠키를 Max-Age=0으로 내려 삭제 */
    public ResponseCookie expiredCookie() {
        return ResponseCookie.from(props.jwt().cookieName(), "")
                .httpOnly(true)
                .secure(props.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
