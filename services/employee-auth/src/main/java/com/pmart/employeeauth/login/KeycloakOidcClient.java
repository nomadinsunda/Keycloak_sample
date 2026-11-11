package com.pmart.employeeauth.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.pmart.employeeauth.config.AuthProperties;

/**
 * Keycloak OIDC 처리 (Employee Auth Service = Confidential OIDC Client).
 * - Authorization URL: 브라우저가 열 주소이므로 Gateway 주소(/iam) 기준
 * - Token Endpoint·JWKS: 서버 간 호출이므로 Gateway를 경유하지 않고 내부 주소로 직접 호출
 */
@Component
public class KeycloakOidcClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthProperties props;
    private final RestClient rest = RestClient.create();
    private final NimbusJwtDecoder idTokenDecoder;

    public KeycloakOidcClient(AuthProperties props) {
        this.props = props;
        this.idTokenDecoder = NimbusJwtDecoder
                .withJwkSetUri(props.keycloakInternalRealmUrl() + "/protocol/openid-connect/certs")
                .build();
        // ID Token 검증: 서명, iss(Gateway 주소 기준), exp, aud(이 Client에 발급된 토큰만)
        this.idTokenDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.keycloakIssuer()),
                new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(props.keycloak().clientId()))));
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** PKCE S256: BASE64URL(SHA256(code_verifier)) */
    public static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String authorizationUrl(LoginTransactionRepository.LoginTransaction tx) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(props.keycloakIssuer() + "/protocol/openid-connect/auth");
        if (tx.client() == LoginTransactionRepository.Client.MOBILE) {
            // 앱 로그아웃은 Keycloak SSO 세션을 끝내지 않으므로, 앱 로그인은 매번 비밀번호를 다시 받음
            builder.queryParam("prompt", "login");
        }
        return builder
                .queryParam("response_type", "code")
                .queryParam("client_id", props.keycloak().clientId())
                .queryParam("redirect_uri", props.callbackUrl())
                .queryParam("scope", "openid profile")
                .queryParam("state", tx.state())
                .queryParam("nonce", tx.nonce())
                .queryParam("code_challenge", codeChallenge(tx.codeVerifier()))
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUriString();
    }

    /** Authorization Code → 토큰 교환 후 ID Token 검증. nonce까지 확인한 ID Token을 반환 */
    public Jwt exchangeAndVerify(String code, LoginTransactionRepository.LoginTransaction tx) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.callbackUrl());
        form.add("code_verifier", tx.codeVerifier());
        Map<String, Object> response = rest.post()
                .uri(props.keycloakInternalRealmUrl() + "/protocol/openid-connect/token")
                .headers(h -> h.setBasicAuth(props.keycloak().clientId(), props.keycloak().clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        // Keycloak Access/Refresh Token은 PMart 업무 API에 사용하지 않으므로 저장하지 않음
        Jwt idToken = idTokenDecoder.decode((String) response.get("id_token"));
        if (!tx.nonce().equals(idToken.getClaimAsString("nonce"))) {
            throw new IllegalStateException("ID Token nonce 불일치");
        }
        return idToken;
    }

    /** Keycloak SSO 세션 로그아웃 주소 (브라우저가 이동) */
    public String endSessionUrl() {
        return UriComponentsBuilder.fromUriString(props.keycloakIssuer() + "/protocol/openid-connect/logout")
                .queryParam("client_id", props.keycloak().clientId())
                .queryParam("post_logout_redirect_uri", props.backofficeOrigin() + "/")
                .encode()
                .build()
                .toUriString();
    }
}
