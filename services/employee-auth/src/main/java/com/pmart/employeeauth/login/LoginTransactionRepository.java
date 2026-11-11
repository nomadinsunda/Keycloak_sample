package com.pmart.employeeauth.login;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * 로그인 트랜잭션 저장소 (PostgreSQL, D13).
 * Callback에서 한 번 사용하면 즉시 삭제하고(재사용 방지), 만료된 값은 주기적으로 정리합니다.
 */
@Repository
public class LoginTransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(LoginTransactionRepository.class);

    private final JdbcClient jdbc;

    public LoginTransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(LoginTransaction tx, Duration ttl) {
        jdbc.sql("""
                INSERT INTO login_transactions (state, nonce, code_verifier, expires_at)
                VALUES (:state, :nonce, :verifier, now() + make_interval(secs => :ttl))
                """)
                .param("state", tx.state()).param("nonce", tx.nonce()).param("verifier", tx.codeVerifier())
                .param("ttl", ttl.toSeconds())
                .update();
    }

    /** state로 꺼내면서 삭제. 만료됐으면 빈 값 */
    public Optional<LoginTransaction> consume(String state) {
        return jdbc.sql("""
                DELETE FROM login_transactions WHERE state = :state
                RETURNING state, nonce, code_verifier, expires_at
                """)
                .param("state", state)
                .query((rs, n) -> new Row(
                        new LoginTransaction(rs.getString("state"), rs.getString("nonce"), rs.getString("code_verifier")),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .filter(row -> row.expiresAt().isAfter(OffsetDateTime.now()))
                .map(Row::tx);
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void deleteExpired() {
        int deleted = jdbc.sql("DELETE FROM login_transactions WHERE expires_at < now()").update();
        if (deleted > 0) {
            log.info("만료된 로그인 트랜잭션 {}건 정리", deleted);
        }
    }

    private record Row(LoginTransaction tx, OffsetDateTime expiresAt) {
    }

    public record LoginTransaction(String state, String nonce, String codeVerifier) {
    }
}
