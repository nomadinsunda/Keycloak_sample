package com.pmart.employeeauth.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.pmart.employeeauth.support.AuthDbTest;

/** 서명키: RSA 2048, DB에 저장해 재시작해도 같은 키, JWKS에는 공개키만 (D14) */
@AuthDbTest
class SigningKeyServiceTest {

    @Autowired
    SigningKeyService keys;

    @Autowired
    JdbcClient jdbc;

    @Test
    void 서명키는_RSA_2048이고_RS256용이다() {
        assertThat(keys.activeKey().size()).isEqualTo(2048);
        assertThat(keys.activeKey().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(keys.activeKey().getKeyID()).isNotBlank();
    }

    @Test
    void 재시작해도_DB에_저장된_같은_키를_쓴다() throws Exception {
        // 같은 DB로 SigningKeyService를 다시 만드는 것 = 서비스 재시작
        SigningKeyService restarted = new SigningKeyService(jdbc);

        assertThat(restarted.activeKey().getKeyID()).isEqualTo(keys.activeKey().getKeyID());
        assertThat(restarted.activeKey().toRSAPublicKey()).isEqualTo(keys.activeKey().toRSAPublicKey());
        assertThat(jdbc.sql("SELECT count(*) FROM signing_keys WHERE active").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void JWKS에는_개인키가_없다() {
        assertThat(keys.publicJwkSet().getKeys()).isNotEmpty().allSatisfy(jwk -> assertThat(jwk.isPrivate()).isFalse());
        // 직렬화 결과에도 RSA 개인키 성분(d, p, q ...)이 없어야 함
        assertThat(keys.publicJwkSet().toJSONObject().toString()).doesNotContain("\"d\"", "\"p\"", "\"q\"");
        assertThat(keys.publicJwkSet().getKeys()).extracting(JWK::getKeyID).contains(keys.activeKey().getKeyID());
    }
}
