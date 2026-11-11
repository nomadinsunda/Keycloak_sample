package com.pmart.employeeauth.login;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.pmart.employeeauth.config.AuthProperties;
import com.pmart.employeeauth.employee.EmployeeDirectoryClient;
import com.pmart.employeeauth.employee.EmployeeDirectoryClient.EmployeeStatus;
import com.pmart.employeeauth.login.LoginTransactionRepository.LoginTransaction;
import com.pmart.employeeauth.token.PmartTokenService;
import com.pmart.employeeauth.token.RefreshTokenService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 직원 로그인 API (Authorization Code + PKCE).
 * 모든 경로는 Gateway의 /employee-auth/** 라우트를 통해 브라우저가 호출합니다.
 */
@RestController
@RequestMapping("/employee-auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthProperties props;
    private final LoginTransactionRepository transactions;
    private final KeycloakOidcClient oidc;
    private final EmployeeDirectoryClient employees;
    private final PmartTokenService tokens;
    private final RefreshTokenService refreshTokens;

    public AuthController(AuthProperties props, LoginTransactionRepository transactions, KeycloakOidcClient oidc,
                          EmployeeDirectoryClient employees, PmartTokenService tokens,
                          RefreshTokenService refreshTokens) {
        this.props = props;
        this.transactions = transactions;
        this.oidc = oidc;
        this.employees = employees;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
    }

    /** 1. 로그인 시작: state·nonce·PKCE 생성·저장 후 Keycloak 로그인 화면(Gateway /iam 경유)으로 이동 */
    @GetMapping("/oauth2/start")
    public ResponseEntity<Void> start() {
        LoginTransaction tx = new LoginTransaction(
                KeycloakOidcClient.randomToken(), KeycloakOidcClient.randomToken(), KeycloakOidcClient.randomToken());
        transactions.save(tx, props.loginTransactionTtl());
        return redirect(oidc.authorizationUrl(tx)).build();
    }

    /** 3~4. Callback: state 검증 → Code 교환 → ID Token 검증 → 직원 확인 → PMart JWT를 Set-Cookie로 전달 */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        if (error != null || code == null || state == null) {
            return loginFailed(error != null ? error : "invalid_request");
        }
        LoginTransaction tx = transactions.consume(state).orElse(null);
        if (tx == null) {
            // 위조된 state, 이미 사용된 state, 만료된 로그인 트랜잭션
            return loginFailed("invalid_state");
        }

        Jwt idToken;
        try {
            idToken = oidc.exchangeAndVerify(code, tx);
        } catch (RuntimeException e) {
            log.warn("Code 교환 또는 ID Token 검증 실패", e);
            return loginFailed("invalid_id_token");
        }

        EmployeeStatus employee = employees.findByKeycloakId(idToken.getSubject()).orElse(null);
        if (employee == null) {
            return loginFailed("unknown_employee");
        }
        if (!employee.active()) {
            return loginFailed("inactive_employee");
        }

        PmartTokenService.IssuedToken token = tokens.issue(employee.employeeNo(), employee.roles(), idToken.getSubject());
        RefreshTokenService.Issued refresh = refreshTokens.issueNew(employee.employeeNo(), idToken.getSubject());
        log.info("직원 로그인 성공: {} roles={}", employee.employeeNo(), employee.roles());
        return redirect(props.backofficeOrigin() + "/")
                .header(HttpHeaders.SET_COOKIE, tokens.accessTokenCookie(token.value()).toString())
                .header(HttpHeaders.SET_COOKIE, tokens.refreshTokenCookie(refresh).toString())
                .build();
    }

    /**
     * Access Token 갱신 (D19). Refresh Token 쿠키로 인증하므로 Gateway가 CSRF를 검사합니다.
     * Rotation: 새 Access·Refresh Token을 쿠키로 내려주고, 사용한 Refresh Token은 다시 쓸 수 없습니다.
     * 갱신 때마다 재직 상태를 다시 확인해 퇴사·비활성화가 최대 Access Token 수명(15분) 안에 반영되게 합니다.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request) {
        String raw = cookieValue(request, props.jwt().refreshCookieName());
        if (raw == null) {
            return sessionEnded("refresh_token_missing");
        }
        RefreshTokenService.RotationResult result = refreshTokens.rotate(raw);
        if (!result.success()) {
            return sessionEnded("refresh_token_" + result.failure().name().toLowerCase());
        }

        RefreshTokenService.Session session = result.session();
        EmployeeStatus employee = employees.findByKeycloakId(session.keycloakSubject()).orElse(null);
        if (employee == null || !employee.active()) {
            refreshTokens.revokeAllFor(session.employeeNo());
            return sessionEnded("inactive_employee");
        }

        PmartTokenService.IssuedToken token = tokens.issue(employee.employeeNo(), employee.roles(), session.keycloakSubject());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokens.accessTokenCookie(token.value()).toString())
                .header(HttpHeaders.SET_COOKIE, tokens.refreshTokenCookie(result.next()).toString())
                .build();
    }

    /** 로그인한 직원 정보 (SPA는 HttpOnly 쿠키를 읽을 수 없으므로 이 API로 조회) */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        EmployeeStatus employee = employees.findByKeycloakId(jwt.getClaimAsString("external_subject")).orElse(null);
        return Map.of(
                "employeeNo", jwt.getSubject(),
                "firstName", employee != null ? employee.firstName() : "",
                "lastName", employee != null ? employee.lastName() : "",
                "roles", jwt.getClaimAsStringList("roles") != null ? jwt.getClaimAsStringList("roles") : List.of(),
                "expiresAt", jwt.getExpiresAt().toString());
    }

    /** 로그아웃: Refresh Token 세션 폐기 + 쿠키 삭제 + Keycloak SSO 세션 로그아웃 주소 반환 (SPA가 이동) */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        String raw = cookieValue(request, props.jwt().refreshCookieName());
        if (raw != null) {
            refreshTokens.revokeFamily(raw);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokens.expiredCookie().toString())
                .header(HttpHeaders.SET_COOKIE, tokens.expiredRefreshCookie().toString())
                .body(Map.of("keycloakLogoutUrl", oidc.endSessionUrl()));
    }

    /** 갱신 실패: 두 쿠키를 지우고 401 → SPA는 로그인 화면으로 이동 */
    private ResponseEntity<Void> sessionEnded(String reason) {
        log.info("토큰 갱신 실패: {}", reason);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, tokens.expiredCookie().toString())
                .header(HttpHeaders.SET_COOKIE, tokens.expiredRefreshCookie().toString())
                .build();
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private ResponseEntity<Void> loginFailed(String reason) {
        log.info("직원 로그인 실패: {}", reason);
        String url = UriComponentsBuilder.fromUriString(props.backofficeOrigin() + "/")
                .queryParam("login_error", reason).build().toUriString();
        return redirect(url).build();
    }

    private static ResponseEntity.BodyBuilder redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url));
    }
}
