package com.pmart.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Gateway 보안 (D15 쿠키→Bearer, D16 CORS, D17 CSRF, Keycloak 라우트).
 *
 * <p>실제 Netty 서버(RANDOM_PORT)로 기동하고, 4개 라우트의 목적지를 모두 {@link StubDownstream}으로 바꿉니다.
 * 그래서 "Gateway가 막았는지"(하위 서비스에 요청이 안 감)와 "무엇을 전달했는지"(Authorization 헤더)를 함께 확인할 수 있습니다.
 * 라우트는 application.yml과 같은 id·경로로 정의하고 목적지만 바꿉니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    static final String BACKOFFICE = "http://localhost:3000";
    static final String COOKIE = "PMART_EMP_AT";

    static final TestIssuer PMART = new TestIssuer("https://employee-auth.pmart.test");
    static final TestIssuer CUSTOMER = new TestIssuer("https://customer-auth.pmart.test");
    static final StubDownstream DOWNSTREAM = new StubDownstream();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("pmart.backoffice-origin", () -> BACKOFFICE);
        registry.add("pmart.jwt.issuer", () -> PMART.issuer);
        registry.add("pmart.jwt.jwk-set-uri", PMART::jwkSetUri);
        // Spring Boot는 리스트를 프로퍼티 소스끼리 합치지 않으므로 uri만 덮어쓰면 id·predicates가 사라짐 → 라우트 전체를 다시 정의
        String[][] routes = { { "keycloak", "/iam/**" }, { "employee-auth", "/employee-auth/**" },
                { "employee-service", "/employee-service/**" }, { "sample-api", "/sample-api/**" } };
        for (int i = 0; i < routes.length; i++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            String id = routes[i][0];
            String path = routes[i][1];
            registry.add(prefix + ".id", () -> id);
            registry.add(prefix + ".uri", DOWNSTREAM::uri);
            registry.add(prefix + ".predicates[0]", () -> "Path=" + path);
        }
    }

    @AfterAll
    static void stop() {
        PMART.close();
        CUSTOMER.close();
        DOWNSTREAM.close();
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        DOWNSTREAM.received.clear();
    }

    String employeeToken() {
        return PMART.sign(PMART.employeeClaims("HR000001", List.of("EMPLOYEE", "HR_ADMIN")).build());
    }

    /** 백오피스처럼 GET /csrf로 토큰을 받음. 쿠키 값과 본문 토큰(마스킹된 값)을 함께 돌려줌 */
    @SuppressWarnings("unchecked")
    CsrfPair fetchCsrf() {
        EntityExchangeResult<Map> result = client.get().uri("/csrf").header(HttpHeaders.ORIGIN, BACKOFFICE)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        ResponseCookie cookie = result.getResponseCookies().getFirst("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN 쿠키").isNotNull();
        return new CsrfPair(cookie.getValue(), (String) result.getResponseBody().get("token"));
    }

    record CsrfPair(String cookie, String headerToken) {
    }

    // ------------------------------------------------------------------
    @Nested
    class 쿠키를_Bearer로_전달 {

        @Test
        void 쿠키의_PMart_JWT를_하위_서비스에_Authorization_Bearer로_전달() {
            String token = employeeToken();

            client.get().uri("/sample-api/hello").cookie(COOKIE, token).exchange().expectStatus().isOk();

            assertThat(DOWNSTREAM.last().path()).isEqualTo("/sample-api/hello");
            assertThat(DOWNSTREAM.last().header("Authorization")).isEqualTo("Bearer " + token);
        }

        @Test
        void Bearer_헤더_요청은_그대로_전달_Flutter() {
            String token = employeeToken();

            client.get().uri("/sample-api/hello").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();

            assertThat(DOWNSTREAM.last().header("Authorization")).isEqualTo("Bearer " + token);
        }

        @Test
        void Keycloak_라우트에는_PMart_JWT를_보내지_않는다() {
            client.get().uri("/iam/realms/pmart-employees/account").cookie(COOKIE, employeeToken())
                    .exchange().expectStatus().isOk();

            assertThat(DOWNSTREAM.last().path()).isEqualTo("/iam/realms/pmart-employees/account");
            assertThat(DOWNSTREAM.last().header("Authorization")).isNull();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class 인증_경로 {

        @Test
        void 토큰_없이_업무_API를_호출하면_401이고_하위_서비스로_가지_않는다() {
            client.get().uri("/sample-api/hello").exchange().expectStatus().isUnauthorized();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void 고객_발급자_토큰은_Gateway에서_401() {
            String customer = CUSTOMER.sign(CUSTOMER.otherUserClaims("CUSTOMER", "pmart-sample-api").build());

            client.get().uri("/sample-api/hello").headers(h -> h.setBearerAuth(customer))
                    .exchange().expectStatus().isUnauthorized();
            assertThat(DOWNSTREAM.received).isEmpty();
            assertThat(CUSTOMER.jwksRequests).hasValue(0);
        }

        @Test
        void Keycloak_로그인_화면은_인증_없이_통과() {
            client.get().uri("/iam/realms/pmart-employees/protocol/openid-connect/auth").exchange().expectStatus().isOk();
        }

        @Test
        void Keycloak_Admin_API는_외부에_노출하지_않는다() {
            client.get().uri("/iam/admin/realms/pmart-employees/users").exchange().expectStatus().is4xxClientError();
            // 유효한 직원 토큰이 있어도 막혀야 함
            client.get().uri("/iam/admin/master/console/").cookie(COOKIE, employeeToken())
                    .exchange().expectStatus().is4xxClientError();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void 라우트_목록에_없는_경로는_거부() {
            client.get().uri("/internal/employees/E0001").headers(h -> h.setBearerAuth(employeeToken()))
                    .exchange().expectStatus().isForbidden();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void 만료된_쿠키가_있어도_로그인_시작은_막히지_않는다() {
            // 공개 경로에서는 쿠키 JWT를 읽지 않음 → 깨진 쿠키 때문에 401이 나면 다시 로그인할 수 없음
            client.get().uri("/employee-auth/oauth2/start").cookie(COOKIE, "expired.or.broken")
                    .exchange().expectStatus().isOk();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class CSRF_Double_Submit_Cookie {

        @Test
        void 쿠키_인증_POST에_CSRF_토큰이_없으면_403() {
            client.post().uri("/employee-service/employees").header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .cookie(COOKIE, employeeToken()).exchange().expectStatus().isForbidden();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void CSRF_쿠키와_헤더가_맞으면_통과() {
            CsrfPair csrf = fetchCsrf();

            client.post().uri("/employee-service/employees").header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .cookie(COOKIE, employeeToken()).cookie("XSRF-TOKEN", csrf.cookie())
                    .header("X-XSRF-TOKEN", csrf.headerToken())
                    .exchange().expectStatus().isOk();
            assertThat(DOWNSTREAM.last().method()).isEqualTo("POST");
        }

        @Test
        void CSRF_헤더_값이_쿠키와_다르면_403() {
            CsrfPair mine = fetchCsrf();
            CsrfPair other = fetchCsrf();

            client.post().uri("/employee-service/employees").header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .cookie(COOKIE, employeeToken()).cookie("XSRF-TOKEN", mine.cookie())
                    .header("X-XSRF-TOKEN", other.headerToken())
                    .exchange().expectStatus().isForbidden();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void PUT_PATCH_DELETE도_검사_대상() {
            for (var method : List.of(org.springframework.http.HttpMethod.PUT, org.springframework.http.HttpMethod.PATCH,
                    org.springframework.http.HttpMethod.DELETE)) {
                client.method(method).uri("/employee-service/employees/E0001").header(HttpHeaders.ORIGIN, BACKOFFICE)
                        .cookie(COOKIE, employeeToken()).exchange().expectStatus().isForbidden();
            }
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void 로그아웃과_Refresh도_쿠키_요청이므로_검사_대상() {
            client.post().uri("/employee-auth/logout").header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .cookie(COOKIE, employeeToken()).exchange().expectStatus().isForbidden();
            client.post().uri("/employee-auth/refresh").header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .cookie("PMART_EMP_RT", "refresh-token").exchange().expectStatus().isForbidden();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void Bearer_헤더_요청은_CSRF_검사_제외_Flutter() {
            String token = employeeToken();

            client.post().uri("/employee-service/employees").headers(h -> h.setBearerAuth(token))
                    .exchange().expectStatus().isOk();
        }

        @Test
        void 앱_전용_mobile_경로는_CSRF_검사_제외() {
            client.post().uri("/employee-auth/mobile/token").bodyValue(Map.of("ticket", "t", "app_verifier", "v"))
                    .exchange().expectStatus().isOk();
        }

        @Test
        void Keycloak_경로는_CSRF_검사_제외_로그인_폼_제출() {
            // Keycloak은 자체 CSRF 방어가 있고, Gateway가 검사하면 로그인 폼 POST가 깨짐
            client.post().uri("/iam/realms/pmart-employees/login-actions/authenticate")
                    .exchange().expectStatus().isOk();
        }

        /**
         * 다른 Origin의 쿠키 인증 POST는 CSRF 토큰이 맞아도 거부.
         * 실제로 막는 것은 CORS 처리기입니다(허용되지 않은 Origin이면 preflight가 아닌 실제 요청도 403).
         * 업무 경로는 모두 CORS 설정에 등록되어 있어 SecurityConfig의 보조 Origin 검사 필터까지 가지 않습니다.
         * (CSRF 검사를 꺼도 이 테스트는 통과함 — 변이 테스트로 확인, docs/verification-log.md)
         */
        @Test
        void CSRF_토큰이_맞아도_다른_Origin이면_CORS에서_403() {
            CsrfPair csrf = fetchCsrf();

            client.post().uri("/employee-service/employees").header(HttpHeaders.ORIGIN, "http://evil.example")
                    .cookie(COOKIE, employeeToken()).cookie("XSRF-TOKEN", csrf.cookie())
                    .header("X-XSRF-TOKEN", csrf.headerToken())
                    .exchange().expectStatus().isForbidden();
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void CSRF_쿠키는_SameSite_Lax_경로_슬래시() {
            ResponseCookie cookie = client.get().uri("/csrf").exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseCookies().getFirst("XSRF-TOKEN");

            assertThat(cookie.getSameSite()).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class CORS {

        @Test
        void 백오피스_Origin의_preflight는_인증_없이_허용되고_쿠키_전송을_허용한다() {
            client.options().uri("/employee-service/employees")
                    .header(HttpHeaders.ORIGIN, BACKOFFICE)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, BACKOFFICE)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            // preflight는 Gateway가 응답하고 하위 서비스로 보내지 않음
            assertThat(DOWNSTREAM.received).isEmpty();
        }

        @Test
        void 다른_Origin의_preflight는_거부() {
            client.options().uri("/employee-service/employees")
                    .header(HttpHeaders.ORIGIN, "http://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }

        @Test
        void 실제_요청에도_백오피스_Origin만_와일드카드_없이_돌려준다() {
            client.get().uri("/sample-api/hello").header(HttpHeaders.ORIGIN, BACKOFFICE).cookie(COOKIE, employeeToken())
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, BACKOFFICE)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }
}
