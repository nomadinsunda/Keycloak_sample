package com.pmart.employeeauth.login;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pmart.employeeauth.employee.EmployeeDirectoryClient;
import com.pmart.employeeauth.employee.EmployeeDirectoryClient.EmployeeStatus;
import com.pmart.employeeauth.token.PmartTokenService;
import com.pmart.employeeauth.token.RefreshTokenService;

/**
 * Flutter 앱 전용 토큰 API (D20).
 * 쿠키를 쓰지 않고 JSON 본문으로 토큰을 주고받으므로 CSRF 대상이 아닙니다(Gateway에서 CSRF 제외).
 * 앱은 Access Token을 Authorization: Bearer 헤더로 보내고, 토큰은 기기의 보안 저장소(Keystore/Keychain)에 보관합니다.
 */
@RestController
@RequestMapping("/employee-auth/mobile")
public class MobileAuthController {

    private static final Logger log = LoggerFactory.getLogger(MobileAuthController.class);

    private final MobileTicketRepository tickets;
    private final EmployeeDirectoryClient employees;
    private final PmartTokenService tokens;
    private final RefreshTokenService refreshTokens;

    public MobileAuthController(MobileTicketRepository tickets, EmployeeDirectoryClient employees,
                                PmartTokenService tokens, RefreshTokenService refreshTokens) {
        this.tickets = tickets;
        this.employees = employees;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
    }

    /** 일회성 ticket + 앱이 보관한 app_verifier → PMart Access·Refresh Token */
    @PostMapping("/token")
    public ResponseEntity<?> token(@Valid @RequestBody TicketRequest request) {
        MobileTicketRepository.Redeemed redeemed = tickets.redeem(request.ticket(), request.appVerifier()).orElse(null);
        if (redeemed == null) {
            // 없는 ticket, 이미 사용한 ticket, 만료, app_verifier 불일치(가로챈 ticket)
            log.info("ticket 교환 실패");
            return error("invalid_ticket");
        }
        EmployeeStatus employee = employees.findByKeycloakId(redeemed.keycloakSubject()).orElse(null);
        if (employee == null || !employee.active()) {
            return error("inactive_employee");
        }
        RefreshTokenService.Issued refresh = refreshTokens.issueNew(employee.employeeNo(), redeemed.keycloakSubject());
        log.info("앱 토큰 발급: {}", employee.employeeNo());
        return ResponseEntity.ok(tokenResponse(employee, redeemed.keycloakSubject(), refresh));
    }

    /** Refresh Token Rotation (백오피스와 같은 정책: 8시간 고정, 재사용 탐지, 재직 상태 재확인) */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenService.RotationResult result = refreshTokens.rotate(request.refreshToken());
        if (!result.success()) {
            return error("refresh_token_" + result.failure().name().toLowerCase());
        }
        RefreshTokenService.Session session = result.session();
        EmployeeStatus employee = employees.findByKeycloakId(session.keycloakSubject()).orElse(null);
        if (employee == null || !employee.active()) {
            refreshTokens.revokeAllFor(session.employeeNo());
            return error("inactive_employee");
        }
        return ResponseEntity.ok(tokenResponse(employee, session.keycloakSubject(), result.next()));
    }

    /** 로그아웃: 이 로그인 세션의 Refresh Token 폐기 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokens.revokeFamily(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> tokenResponse(EmployeeStatus employee, String keycloakSubject,
                                              RefreshTokenService.Issued refresh) {
        PmartTokenService.IssuedToken access = tokens.issue(employee.employeeNo(), employee.roles(), keycloakSubject);
        return Map.of(
                "tokenType", "Bearer",
                "accessToken", access.value(),
                "accessTokenExpiresAt", access.expiresAt().toString(),
                "refreshToken", refresh.value(),
                "refreshTokenExpiresAt", refresh.expiresAt().toInstant().toString());
    }

    private static ResponseEntity<Map<String, Object>> error(String code) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", code, "timestamp", Instant.now().toString()));
    }

    public record TicketRequest(@NotBlank String ticket, @NotBlank String appVerifier) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
