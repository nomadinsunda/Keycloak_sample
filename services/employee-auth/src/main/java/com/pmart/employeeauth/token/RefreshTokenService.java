package com.pmart.employeeauth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pmart.employeeauth.config.AuthProperties;
import com.pmart.employeeauth.login.KeycloakOidcClient;

/**
 * PMart Refresh Token (D19).
 * - 무작위 값(256bit), DB에는 SHA-256 해시만 저장
 * - Rotation: 사용할 때마다 새 토큰 발급, 이전 토큰은 used 처리
 * - 재사용 탐지: 이미 사용된 토큰이 다시 오면 같은 family(로그인 세션) 전체 폐기
 * - 수명: 로그인 시점 + 8시간 고정 (Rotation해도 연장하지 않음)
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final JdbcClient jdbc;
    private final AuthProperties props;

    public RefreshTokenService(JdbcClient jdbc, AuthProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 로그인 성공 시 새 family로 발급 */
    public Issued issueNew(String employeeNo, String keycloakSubject) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(props.jwt().refreshTokenTtl());
        return insert(UUID.randomUUID(), employeeNo, keycloakSubject, expiresAt);
    }

    /** Rotation. 실패 사유: INVALID(없음·만료·폐기), REUSED(재사용 탐지 → family 폐기) */
    @Transactional
    public RotationResult rotate(String rawToken) {
        String hash = hash(rawToken);
        Optional<Session> used = jdbc.sql("""
                UPDATE refresh_tokens SET used_at = now()
                WHERE token_hash = :hash AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                RETURNING family_id, employee_no, keycloak_subject, expires_at
                """)
                .param("hash", hash)
                .query((rs, n) -> new Session(rs.getObject("family_id", UUID.class), rs.getString("employee_no"),
                        rs.getString("keycloak_subject"), rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
        if (used.isPresent()) {
            Session s = used.get();
            return RotationResult.ok(s, insert(s.familyId(), s.employeeNo(), s.keycloakSubject(), s.expiresAt()));
        }

        // 이미 사용된 토큰이 다시 왔으면 탈취 가능성 → 같은 로그인 세션 전체 폐기
        Optional<UUID> reusedFamily = jdbc.sql("""
                SELECT family_id FROM refresh_tokens
                WHERE token_hash = :hash AND used_at IS NOT NULL AND revoked_at IS NULL
                """).param("hash", hash).query(UUID.class).optional();
        if (reusedFamily.isPresent()) {
            int revoked = revokeFamilyById(reusedFamily.get());
            log.warn("Refresh Token 재사용 탐지: family={} 폐기 {}건", reusedFamily.get(), revoked);
            return RotationResult.failed(RotationResult.Failure.REUSED);
        }
        return RotationResult.failed(RotationResult.Failure.INVALID);
    }

    /** 로그아웃: 이 토큰이 속한 로그인 세션 폐기 */
    public void revokeFamily(String rawToken) {
        jdbc.sql("SELECT family_id FROM refresh_tokens WHERE token_hash = :hash")
                .param("hash", hash(rawToken)).query(UUID.class).optional()
                .ifPresent(this::revokeFamilyById);
    }

    /** 퇴사·비활성화: 이 직원의 모든 로그인 세션 폐기 */
    public int revokeAllFor(String employeeNo) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE employee_no = :no AND revoked_at IS NULL")
                .param("no", employeeNo).update();
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void deleteExpired() {
        int deleted = jdbc.sql("DELETE FROM refresh_tokens WHERE expires_at < now() - interval '1 day'").update();
        if (deleted > 0) {
            log.info("만료된 Refresh Token {}건 정리", deleted);
        }
    }

    private int revokeFamilyById(UUID familyId) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
                .param("f", familyId).update();
    }

    private Issued insert(UUID familyId, String employeeNo, String keycloakSubject, OffsetDateTime expiresAt) {
        String raw = KeycloakOidcClient.randomToken();
        jdbc.sql("""
                INSERT INTO refresh_tokens (token_hash, family_id, employee_no, keycloak_subject, expires_at)
                VALUES (:hash, :family, :no, :sub, :exp)
                """)
                .param("hash", hash(raw)).param("family", familyId).param("no", employeeNo)
                .param("sub", keycloakSubject).param("exp", expiresAt)
                .update();
        return new Issued(raw, expiresAt);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String value, OffsetDateTime expiresAt) {
    }

    public record Session(UUID familyId, String employeeNo, String keycloakSubject, OffsetDateTime expiresAt) {
    }

    public record RotationResult(Session session, Issued next, Failure failure) {
        public enum Failure { INVALID, REUSED }

        static RotationResult ok(Session session, Issued next) {
            return new RotationResult(session, next, null);
        }

        static RotationResult failed(Failure failure) {
            return new RotationResult(null, null, failure);
        }

        public boolean success() {
            return failure == null;
        }
    }
}
