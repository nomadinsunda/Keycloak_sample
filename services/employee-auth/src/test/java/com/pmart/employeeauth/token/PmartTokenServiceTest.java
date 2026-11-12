package com.pmart.employeeauth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jwt.SignedJWT;
import com.pmart.employeeauth.support.AuthDbTest;
import com.pmart.employeeauth.support.TestAuthConfiguration;

/** PMart Access Token 형식(D14)과 백오피스 쿠키 속성(D15) */
@AuthDbTest
class PmartTokenServiceTest {

    private static final String KEYCLOAK_SUB = "8a3c5d2e-1b4f-4e6a-9c7d-0e1f2a3b4c5d";

    @Autowired
    PmartTokenService tokens;

    @Autowired
    SigningKeyService keys;

    @Test
    void 헤더는_RS256과_활성키_kid() throws Exception {
        String token = tokens.issue("E0001", List.of("EMPLOYEE"), KEYCLOAK_SUB).value();

        var header = SignedJWT.parse(token).getHeader();
        assertThat(header.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(header.getKeyID()).isEqualTo(keys.activeKey().getKeyID());
    }

    @Test
    void 공개키로_서명이_검증되고_클레임이_설계와_같다() throws Exception {
        String token = tokens.issue("E0001", List.of("EMPLOYEE", "HR_ADMIN"), KEYCLOAK_SUB).value();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(keys.activeKey().toRSAPublicKey()).build().decode(token);

        assertThat(jwt.getIssuer().toString()).isEqualTo(TestAuthConfiguration.ISSUER);
        assertThat(jwt.getSubject()).isEqualTo("E0001");  // sub = 사번
        assertThat(jwt.getAudience()).containsExactlyInAnyOrderElementsOf(TestAuthConfiguration.AUDIENCES);
        assertThat(jwt.getClaimAsString("user_type")).isEqualTo("EMPLOYEE");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("EMPLOYEE", "HR_ADMIN");
        assertThat(jwt.getClaimAsString("identity_provider")).isEqualTo("keycloak");
        assertThat(jwt.getClaimAsString("external_subject")).isEqualTo(KEYCLOAK_SUB);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void 클레임은_정해진_것만_있고_개인정보가_없다() throws Exception {
        String token = tokens.issue("E0001", List.of("EMPLOYEE"), KEYCLOAK_SUB).value();

        // 이름·이메일·IAM 계정 ID 같은 개인정보 클레임이 추가되면 이 테스트가 실패해야 함
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder(
                "iss", "sub", "aud", "iat", "exp", "jti",
                "user_type", "roles", "identity_provider", "external_subject");
    }

    @Test
    void jti는_토큰마다_다르다() throws Exception {
        String a = tokens.issue("E0001", List.of("EMPLOYEE"), KEYCLOAK_SUB).value();
        String b = tokens.issue("E0001", List.of("EMPLOYEE"), KEYCLOAK_SUB).value();

        assertThat(SignedJWT.parse(a).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(b).getJWTClaimsSet().getJWTID());
    }

    @Test
    void Access_Token_쿠키_속성() {
        ResponseCookie cookie = tokens.accessTokenCookie("token-value");

        assertThat(cookie.getName()).isEqualTo("PMART_EMP_AT");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(15));
        // 테스트 설정의 public-base-url이 http이므로 Secure 없음 (HTTP에서 Secure 쿠키는 저장되지 않음)
        assertThat(cookie.isSecure()).isFalse();
    }

    @Test
    void Refresh_Token_쿠키는_employee_auth_경로에만_실린다() {
        var refresh = new RefreshTokenService.Issued("rt", java.time.OffsetDateTime.now().plusHours(8));
        ResponseCookie cookie = tokens.refreshTokenCookie(refresh);

        assertThat(cookie.getName()).isEqualTo("PMART_EMP_RT");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/employee-auth");
        assertThat(cookie.getMaxAge()).isBetween(Duration.ofHours(8).minusMinutes(1), Duration.ofHours(8));
    }

    @Test
    void 로그아웃_쿠키는_같은_이름_같은_경로에_Max_Age_0() {
        assertThat(tokens.expiredCookie().getName()).isEqualTo("PMART_EMP_AT");
        assertThat(tokens.expiredCookie().getPath()).isEqualTo("/");
        assertThat(tokens.expiredCookie().getMaxAge()).isZero();
        assertThat(tokens.expiredRefreshCookie().getName()).isEqualTo("PMART_EMP_RT");
        assertThat(tokens.expiredRefreshCookie().getPath()).isEqualTo("/employee-auth");
        assertThat(tokens.expiredRefreshCookie().getMaxAge()).isZero();
    }
}
