package com.pmart.employeeauth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.pmart.employeeauth.login.LoginTransactionRepository.Client;
import com.pmart.employeeauth.login.LoginTransactionRepository.LoginTransaction;
import com.pmart.employeeauth.support.AuthDbTest;

/** 로그인 트랜잭션(state·nonce·PKCE code_verifier, D13): PostgreSQL 저장, 한 번 쓰면 삭제, 만료되면 사용 불가 */
@AuthDbTest
class LoginTransactionRepositoryTest {

    @Autowired
    LoginTransactionRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    void state로_꺼내면_저장한_값이_그대로_나온다() {
        repository.save(LoginTransaction.backoffice("state-1", "nonce-1", "verifier-1"), Duration.ofMinutes(5));

        var tx = repository.consume("state-1").orElseThrow();

        assertThat(tx.nonce()).isEqualTo("nonce-1");
        assertThat(tx.codeVerifier()).isEqualTo("verifier-1");
        assertThat(tx.client()).isEqualTo(Client.BACKOFFICE);
        assertThat(tx.appChallenge()).isNull();
    }

    @Test
    void 앱_로그인은_app_challenge를_함께_보관한다() {
        repository.save(new LoginTransaction("state-m", "nonce-m", "verifier-m", Client.MOBILE, "challenge-m"),
                Duration.ofMinutes(5));

        var tx = repository.consume("state-m").orElseThrow();

        assertThat(tx.client()).isEqualTo(Client.MOBILE);
        assertThat(tx.appChallenge()).isEqualTo("challenge-m");
    }

    @Test
    void 한_번_사용하면_삭제되어_같은_state로_다시_쓸_수_없다() {
        repository.save(LoginTransaction.backoffice("state-2", "nonce-2", "verifier-2"), Duration.ofMinutes(5));

        assertThat(repository.consume("state-2")).isPresent();
        assertThat(repository.consume("state-2")).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void 만료된_트랜잭션은_사용할_수_없고_꺼낼_때_삭제된다() {
        repository.save(LoginTransaction.backoffice("state-3", "nonce-3", "verifier-3"), Duration.ofSeconds(-1));

        assertThat(repository.consume("state-3")).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void 모르는_state는_빈_값() {
        assertThat(repository.consume("forged-state")).isEmpty();
    }

    @Test
    void 주기_정리는_만료된_것만_지운다() {
        repository.save(LoginTransaction.backoffice("expired", "n", "v"), Duration.ofSeconds(-1));
        repository.save(LoginTransaction.backoffice("alive", "n", "v"), Duration.ofMinutes(5));

        repository.deleteExpired();

        assertThat(repository.consume("alive")).isPresent();
        assertThat(count()).isZero();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM login_transactions").query(Long.class).single();
    }
}
