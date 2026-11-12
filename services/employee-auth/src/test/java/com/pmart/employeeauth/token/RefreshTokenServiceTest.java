package com.pmart.employeeauth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.pmart.employeeauth.support.AuthDbTest;
import com.pmart.employeeauth.token.RefreshTokenService.RotationResult.Failure;

/** Refresh Token (D19): 해시만 저장, 8시간 고정, Rotation, 재사용 탐지 시 로그인 세션 전체 폐기, 퇴사 시 전부 폐기 */
@AuthDbTest
class RefreshTokenServiceTest {

    private static final String SUB = "8a3c5d2e-1b4f-4e6a-9c7d-0e1f2a3b4c5d";

    @Autowired
    RefreshTokenService refresh;

    @Autowired
    JdbcClient jdbc;

    @Test
    void DB에는_원문이_아닌_SHA256_해시만_저장된다() {
        var issued = refresh.issueNew("E0001", SUB);

        String stored = jdbc.sql("SELECT token_hash FROM refresh_tokens WHERE employee_no = 'E0001'")
                .query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(issued.value()).doesNotContain(issued.value());
        assertThat(stored).isEqualTo(sha256Hex(issued.value()));
    }

    @Test
    void 수명은_로그인_시점부터_8시간() {
        var issued = refresh.issueNew("E0001", SUB);

        assertThat(Duration.between(OffsetDateTime.now(), issued.expiresAt()))
                .isBetween(Duration.ofHours(8).minusMinutes(1), Duration.ofHours(8));
    }

    @Test
    void Rotation하면_새_토큰을_주고_만료_시각은_연장하지_않는다() {
        var first = refresh.issueNew("E0001", SUB);

        var result = refresh.rotate(first.value());

        assertThat(result.success()).isTrue();
        assertThat(result.session().employeeNo()).isEqualTo("E0001");
        assertThat(result.session().keycloakSubject()).isEqualTo(SUB);
        assertThat(result.next().value()).isNotEqualTo(first.value());
        // DB 왕복으로 마이크로초 이하가 잘릴 수 있으므로 같은 시각인지만 비교
        assertThat(result.next().expiresAt().toInstant()).isCloseTo(first.expiresAt().toInstant(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void 새_토큰으로_다시_Rotation할_수_있다() {
        var first = refresh.issueNew("E0001", SUB);
        var second = refresh.rotate(first.value()).next();

        assertThat(refresh.rotate(second.value()).success()).isTrue();
    }

    @Test
    void 사용된_토큰이_다시_오면_재사용으로_보고_세션_전체를_폐기한다() {
        var first = refresh.issueNew("E0001", SUB);
        var second = refresh.rotate(first.value()).next();

        // 탈취된 이전 토큰이 다시 사용됨
        var reuse = refresh.rotate(first.value());

        assertThat(reuse.success()).isFalse();
        assertThat(reuse.failure()).isEqualTo(Failure.REUSED);
        // 정상 사용자가 가진 최신 토큰도 함께 폐기되어야 함
        assertThat(refresh.rotate(second.value()).failure()).isEqualTo(Failure.INVALID);
    }

    @Test
    void 재사용_탐지는_다른_로그인_세션에_영향을_주지_않는다() {
        var phone = refresh.issueNew("E0001", SUB);
        var browser = refresh.issueNew("E0001", SUB);
        refresh.rotate(phone.value());

        refresh.rotate(phone.value());  // 재사용 → phone 세션만 폐기

        assertThat(refresh.rotate(browser.value()).success()).isTrue();
    }

    @Test
    void 모르는_토큰은_INVALID() {
        assertThat(refresh.rotate("not-issued").failure()).isEqualTo(Failure.INVALID);
    }

    @Test
    void 만료된_토큰은_INVALID() {
        var issued = refresh.issueNew("E0001", SUB);
        jdbc.sql("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'").update();

        assertThat(refresh.rotate(issued.value()).failure()).isEqualTo(Failure.INVALID);
    }

    @Test
    void 로그아웃하면_그_세션의_토큰은_갱신할_수_없다() {
        var issued = refresh.issueNew("E0001", SUB);

        refresh.revokeFamily(issued.value());

        assertThat(refresh.rotate(issued.value()).failure()).isEqualTo(Failure.INVALID);
    }

    @Test
    void 퇴사_처리는_그_직원의_모든_세션을_폐기하고_다른_직원은_그대로() {
        var a1 = refresh.issueNew("E0001", SUB);
        var a2 = refresh.issueNew("E0001", SUB);
        var other = refresh.issueNew("E0002", "1f2e3d4c-5b6a-4978-8695-a4b3c2d1e0f9");

        int revoked = refresh.revokeAllFor("E0001");

        assertThat(revoked).isEqualTo(2);
        assertThat(refresh.rotate(a1.value()).success()).isFalse();
        assertThat(refresh.rotate(a2.value()).success()).isFalse();
        assertThat(refresh.rotate(other.value()).success()).isTrue();
    }

    private static String sha256Hex(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
