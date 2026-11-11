package com.pmart.employeeauth.token;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PMart JWT 공개키(JWKS).
 * Gateway·Employee Service·샘플 업무 API가 내부 네트워크에서 직접 호출합니다.
 * Gateway 라우트(/employee-auth/**) 밖의 경로이므로 외부에 노출되지 않습니다.
 */
@RestController
public class JwksController {

    private final SigningKeyService keys;

    public JwksController(SigningKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }
}
