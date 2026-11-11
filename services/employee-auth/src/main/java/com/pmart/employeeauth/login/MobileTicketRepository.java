package com.pmart.employeeauth.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Flutter 앱용 일회성 ticket (D20).
 * - 원문은 앱 복귀 URL에만 싣고, DB에는 SHA-256 해시만 저장
 * - 단기 만료(기본 60초), 단일 사용(꺼내면서 삭제)
 * - 로그인을 시작한 앱의 app_verifier가 있어야 교환 가능 → 커스텀 스킴을 가로챈 앱은 ticket만으로 토큰을 받을 수 없음
 */
@Repository
public class MobileTicketRepository {

    private final JdbcClient jdbc;

    public MobileTicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** ticket을 만들어 저장하고 원문을 반환 */
    public String create(String appChallenge, String employeeNo, String keycloakSubject, Duration ttl) {
        String ticket = KeycloakOidcClient.randomToken();
        jdbc.sql("""
                INSERT INTO mobile_login_tickets (ticket_hash, app_challenge, employee_no, keycloak_subject, expires_at)
                VALUES (:hash, :challenge, :no, :sub, now() + make_interval(secs => :ttl))
                """)
                .param("hash", sha256(ticket)).param("challenge", appChallenge).param("no", employeeNo)
                .param("sub", keycloakSubject).param("ttl", ttl.toSeconds())
                .update();
        return ticket;
    }

    /**
     * ticket 교환. 꺼내면서 삭제하므로 app_verifier가 틀려도 그 ticket은 다시 쓸 수 없습니다.
     * (verifier를 추측하며 여러 번 시도하는 공격 방지)
     */
    public Optional<Redeemed> redeem(String ticket, String appVerifier) {
        return jdbc.sql("""
                DELETE FROM mobile_login_tickets WHERE ticket_hash = :hash
                RETURNING app_challenge, employee_no, keycloak_subject, expires_at
                """)
                .param("hash", sha256(ticket))
                .query((rs, n) -> new Row(rs.getString("app_challenge"), rs.getString("employee_no"),
                        rs.getString("keycloak_subject"), rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .filter(row -> row.expiresAt().isAfter(OffsetDateTime.now()))
                .filter(row -> MessageDigest.isEqual(
                        row.appChallenge().getBytes(StandardCharsets.US_ASCII),
                        KeycloakOidcClient.codeChallenge(appVerifier).getBytes(StandardCharsets.US_ASCII)))
                .map(row -> new Redeemed(row.employeeNo(), row.keycloakSubject()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(String appChallenge, String employeeNo, String keycloakSubject, OffsetDateTime expiresAt) {
    }

    public record Redeemed(String employeeNo, String keycloakSubject) {
    }
}
