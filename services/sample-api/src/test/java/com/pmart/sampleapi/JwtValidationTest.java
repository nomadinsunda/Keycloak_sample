package com.pmart.sampleapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nimbusds.jwt.JWTClaimsSet;

/**
 * 샘플 업무 API의 PMart JWT 검증 (설계 원칙 5·7, D14).
 *
 * <p>운영 SecurityConfig의 JwtDecoder를 그대로 쓰고, jwk-set-uri만 테스트용 PMart 발급자의 JWKS로 바꿉니다.
 * 고객·라이더 토큰은 각자의 테스트용 키로 서명합니다(실제 Customer/Rider Auth Service는 구현하지 않음).
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtValidationTest {

    static final TestIssuer PMART = new TestIssuer("https://employee-auth.pmart.test");
    static final TestIssuer CUSTOMER = new TestIssuer("https://customer-auth.pmart.test");
    static final TestIssuer RIDER = new TestIssuer("https://rider-auth.pmart.test");

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("pmart.jwt.issuer", () -> PMART.issuer);
        registry.add("pmart.jwt.jwk-set-uri", PMART::jwkSetUri);
    }

    @AfterAll
    static void stop() {
        PMART.close();
        CUSTOMER.close();
        RIDER.close();
    }

    @Autowired
    MockMvc mvc;

    // ---- 허용 ----

    @Test
    void 직원_토큰으로_호출하면_200과_사번() throws Exception {
        call("/sample-api/hello", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE")).build()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNo").value("E0001"));
    }

    @Test
    void HR_ADMIN은_인사팀_API_호출_가능() throws Exception {
        call("/sample-api/hr-only", PMART.sign(PMART.employeeClaims("HR000001", List.of("EMPLOYEE", "HR_ADMIN")).build()))
                .andExpect(status().isOk());
    }

    // ---- 인증·권한 ----

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(get("/sample-api/hello")).andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_직원은_인사팀_API에서_403() throws Exception {
        call("/sample-api/hr-only", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE")).build()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 정의되지_않은_경로는_유효한_토큰이어도_403() throws Exception {
        call("/admin", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE")).build()))
                .andExpect(status().isForbidden());
    }

    // ---- 교차 접근 거부: 다른 발급자 (PMart 발급자 3개 분리) ----

    @Test
    void 고객_토큰은_거부() throws Exception {
        call("/sample-api/hello", CUSTOMER.sign(CUSTOMER.otherUserClaims("CUSTOMER", "pmart-sample-api").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 라이더_토큰은_거부() throws Exception {
        call("/sample-api/hello", RIDER.sign(RIDER.otherUserClaims("RIDER", "pmart-sample-api").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 고객_키로_직원_토큰을_흉내내도_서명이_달라_거부() throws Exception {
        // iss·user_type·aud를 모두 PMart 직원 토큰처럼 꾸몄지만 고객 발급자의 키로 서명
        String forged = TestIssuer.sign(PMART.employeeClaims("HR000001", List.of("HR_ADMIN")).build(), CUSTOMER.key);

        call("/sample-api/hr-only", forged).andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰의_iss를_따라_다른_발급자의_JWKS를_가져오지_않는다() throws Exception {
        CUSTOMER.jwksRequests.set(0);
        // 고객 발급자 키로 서명하고 iss도 고객 발급자 → 허용 목록에 없으므로 그 JWKS를 조회하면 안 됨
        call("/sample-api/hello", CUSTOMER.sign(CUSTOMER.otherUserClaims("EMPLOYEE", "pmart-sample-api").build()))
                .andExpect(status().isUnauthorized());

        assertThat(CUSTOMER.jwksRequests).hasValue(0);
    }

    // ---- PMart 키로 서명했지만 클레임이 맞지 않음 ----

    @Test
    void 허용되지_않은_iss는_PMart_키로_서명돼도_거부() throws Exception {
        call("/sample-api/hello", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE"))
                .issuer("https://customer-auth.pmart.test").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aud에_샘플_API가_없으면_거부() throws Exception {
        call("/sample-api/hello", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE"))
                .audience("pmart-employee-service").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void user_type이_EMPLOYEE가_아니면_거부() throws Exception {
        call("/sample-api/hello", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE"))
                .claim("user_type", "CUSTOMER").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 만료된_토큰은_거부() throws Exception {
        // 기본 허용 시계 오차(60초)보다 충분히 지난 토큰
        Instant past = Instant.now().minusSeconds(3600);
        call("/sample-api/hello", PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE"))
                .issueTime(Date.from(past.minusSeconds(900))).expirationTime(Date.from(past)).build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서명_없는_토큰_alg_none은_거부() throws Exception {
        JWTClaimsSet claims = PMART.employeeClaims("HR000001", List.of("HR_ADMIN")).build();
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String unsigned = header + "." + b64(claims.toString()) + ".";

        call("/sample-api/hr-only", unsigned).andExpect(status().isUnauthorized());
    }

    @Test
    void 서명을_변조한_토큰은_거부() throws Exception {
        String token = PMART.sign(PMART.employeeClaims("E0001", List.of("EMPLOYEE")).build());
        // 서명은 그대로 두고 payload만 HR_ADMIN으로 바꿔치기
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + b64(PMART.employeeClaims("E0001", List.of("HR_ADMIN")).build().toString())
                + "." + parts[2];

        call("/sample-api/hr-only", tampered).andExpect(status().isUnauthorized());
    }

    private ResultActions call(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
