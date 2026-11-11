package com.pmart.employeeauth.login;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pmart.employeeauth.token.RefreshTokenService;

/**
 * 서비스 간 내부 API (Employee Service 전용).
 * Gateway 라우트(/employee-auth/**) 밖의 경로이므로 외부에서 접근할 수 없고, 내부 API 키로 보호합니다.
 */
@RestController
@RequestMapping("/internal/sessions")
public class InternalSessionController {

    private static final Logger log = LoggerFactory.getLogger(InternalSessionController.class);

    private final RefreshTokenService refreshTokens;

    public InternalSessionController(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /** 퇴사·비활성화 시 그 직원의 모든 PMart 로그인 세션(Refresh Token) 폐기 */
    @PostMapping("/revoke")
    public Map<String, Object> revoke(@Valid @RequestBody RevokeRequest request) {
        int revoked = refreshTokens.revokeAllFor(request.employeeNo());
        log.info("직원 세션 폐기: {} Refresh Token {}건", request.employeeNo(), revoked);
        return Map.of("employeeNo", request.employeeNo(), "revokedRefreshTokens", revoked);
    }

    public record RevokeRequest(@NotBlank String employeeNo) {
    }
}
