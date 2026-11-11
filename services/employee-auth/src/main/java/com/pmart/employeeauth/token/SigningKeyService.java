package com.pmart.employeeauth.token;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * PMart JWT 서명키(RS256, RSA 2048) 관리.
 * 부팅 시 PostgreSQL에 활성 키가 없으면 새로 만들어 저장하고, 재시작해도 같은 키를 씁니다.
 * JWKS에는 저장된 모든 공개키를 내보내므로, 키를 교체해도 이전 키로 서명된 토큰을 검증할 수 있습니다.
 */
@Component
public class SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final RSAKey activeKey;
    private final JWKSet publicJwkSet;

    public SigningKeyService(JdbcClient jdbc) throws Exception {
        if (jdbc.sql("SELECT count(*) FROM signing_keys WHERE active").query(Long.class).single() == 0) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO signing_keys (kid, private_key, public_key, active) VALUES (:kid, :priv, :pub, true)")
                    .param("kid", kid)
                    .param("priv", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
                    .param("pub", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .update();
            log.info("새 PMart JWT 서명키를 생성했습니다. kid={}", kid);
        }

        List<StoredKey> keys = jdbc.sql("SELECT kid, private_key, public_key, active FROM signing_keys ORDER BY created_at DESC")
                .query(StoredKey.class).list();
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAKey active = null;
        List<com.nimbusds.jose.jwk.JWK> publicKeys = new java.util.ArrayList<>();
        for (StoredKey k : keys) {
            RSAPublicKey pub = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(k.publicKey())));
            RSAKey.Builder builder = new RSAKey.Builder(pub).keyID(k.kid()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256);
            publicKeys.add(builder.build());
            if (k.active() && active == null) {
                RSAPrivateKey priv = (RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(k.privateKey())));
                active = builder.privateKey(priv).build();
            }
        }
        this.activeKey = active;
        this.publicJwkSet = new JWKSet(publicKeys);
        log.info("PMart JWT 서명키 로드 완료. 활성 kid={}, 공개키 {}개", active.getKeyID(), publicKeys.size());
    }

    /** 서명용 활성 키 (개인키 포함) */
    public RSAKey activeKey() {
        return activeKey;
    }

    /** JWKS 엔드포인트용 공개키 목록 */
    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }

    record StoredKey(String kid, String privateKey, String publicKey, boolean active) {
    }
}
