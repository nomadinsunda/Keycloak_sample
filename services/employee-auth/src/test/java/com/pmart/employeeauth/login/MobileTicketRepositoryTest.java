package com.pmart.employeeauth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.pmart.employeeauth.support.AuthDbTest;

/**
 * Flutter 앱 일회성 ticket (D20, 설계 원칙 8).
 * 커스텀 스킴은 다른 앱이 가로챌 수 있으므로 ticket은 로그인을 시작한 앱의 app_verifier에 결속되고,
 * 단기 만료·단일 사용이며 교환에 실패해도 폐기되어야 합니다.
 */
@AuthDbTest
class MobileTicketRepositoryTest {

    private static final String SUB = "8a3c5d2e-1b4f-4e6a-9c7d-0e1f2a3b4c5d";
    private static final Duration TTL = Duration.ofSeconds(60);

    @Autowired
    MobileTicketRepository tickets;

    @Autowired
    JdbcClient jdbc;

    private final String appVerifier = KeycloakOidcClient.randomToken();
    private final String appChallenge = KeycloakOidcClient.codeChallenge(appVerifier);

    @Test
    void 올바른_app_verifier로_교환하면_직원_정보를_돌려준다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, TTL);

        var redeemed = tickets.redeem(ticket, appVerifier).orElseThrow();

        assertThat(redeemed.employeeNo()).isEqualTo("E0001");
        assertThat(redeemed.keycloakSubject()).isEqualTo(SUB);
    }

    @Test
    void DB에는_ticket_원문이_없다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, TTL);

        String stored = jdbc.sql("SELECT ticket_hash FROM mobile_login_tickets").query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(ticket);
    }

    @Test
    void 같은_ticket은_한_번만_교환된다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, TTL);

        assertThat(tickets.redeem(ticket, appVerifier)).isPresent();
        assertThat(tickets.redeem(ticket, appVerifier)).isEmpty();
    }

    @Test
    void 가로챈_앱은_ticket만으로_교환할_수_없다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, TTL);
        String attackerVerifier = KeycloakOidcClient.randomToken();

        assertThat(tickets.redeem(ticket, attackerVerifier)).isEmpty();
    }

    @Test
    void 교환에_실패한_ticket은_폐기되어_정상_verifier로도_쓸_수_없다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, TTL);
        tickets.redeem(ticket, "wrong-verifier");

        // verifier를 추측하며 여러 번 시도하는 공격을 막기 위해 첫 실패에서 폐기
        assertThat(tickets.redeem(ticket, appVerifier)).isEmpty();
    }

    @Test
    void 만료된_ticket은_교환할_수_없다() {
        String ticket = tickets.create(appChallenge, "E0001", SUB, Duration.ofSeconds(-1));

        assertThat(tickets.redeem(ticket, appVerifier)).isEmpty();
    }

    @Test
    void PKCE_S256_계산은_RFC_7636_예시와_같다() {
        // RFC 7636 Appendix B의 예시 값
        assertThat(KeycloakOidcClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
