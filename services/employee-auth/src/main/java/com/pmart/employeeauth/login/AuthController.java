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

    public AuthController(AuthProperties props, LoginTransactionRepository transactions, KeycloakOidcClient oidc,
                          EmployeeDirectoryClient employees, PmartTokenService tokens) {
        this.props = props;
        this.transactions = transactions;
        this.oidc = oidc;
        this.employees = employees;
        this.tokens = tokens;
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
        log.info("직원 로그인 성공: {} roles={}", employee.employeeNo(), employee.roles());
        return redirect(props.backofficeOrigin() + "/")
                .header(HttpHeaders.SET_COOKIE, tokens.accessTokenCookie(token.value()).toString())
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

    /** 로그아웃: PMart JWT 쿠키 삭제 + Keycloak SSO 세션 로그아웃 주소 반환 (SPA가 이동) */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokens.expiredCookie().toString())
                .body(Map.of("keycloakLogoutUrl", oidc.endSessionUrl()));
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
