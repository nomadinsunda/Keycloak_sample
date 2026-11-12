package com.pmart.employeeservice.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import com.pmart.employeeservice.TestcontainersConfiguration;
import com.pmart.employeeservice.keycloak.KeycloakAdminClient;

/**
 * Employee Service API: HR_ADMIN 권한, 입력값 검증 메시지, IAM 계정 발급(D21)·메일함(D22), 퇴사 처리(D19), 내부 API 키.
 *
 * <p>DB는 Testcontainers PostgreSQL, Keycloak Admin REST API와 Employee Auth Service 호출은 Mockito로 대신합니다.
 * PMart JWT는 spring-security-test의 jwt()로 넣습니다. 이 방식은 JwtDecoder를 거치지 않으므로
 * 서명·iss·aud·user_type 검증은 같은 코드를 쓰는 sample-api의 JwtValidationTest에서 확인합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmployeeApiTest {

    static final String INTERNAL_KEY = "test-internal-key";

    @TempDir
    static Path mailConfig;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("pmart.internal.api-key", () -> INTERNAL_KEY);
        registry.add("pmart.mail.accounts-file", () -> mailConfig.resolve("postfix-accounts.cf").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    KeycloakAdminClient keycloak;

    @MockitoBean
    EmployeeAuthSessionClient sessions;

    @BeforeEach
    void clean() throws IOException {
        jdbc.sql("DELETE FROM employees").update();
        Files.deleteIfExists(accountsFile());
        reset(keycloak, sessions);
        given(keycloak.ensureUser(anyString(), anyString(), anyString(), anyString()))
                .willAnswer(inv -> "kc-" + inv.getArgument(0, String.class).hashCode());
    }

    static JwtRequestPostProcessor hrAdmin() {
        return jwt().jwt(j -> j.subject("HR000001").claim("roles", List.of("EMPLOYEE", "HR_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"), new SimpleGrantedAuthority("ROLE_HR_ADMIN"));
    }

    static JwtRequestPostProcessor employee() {
        return jwt().jwt(j -> j.subject("E0001").claim("roles", List.of("EMPLOYEE")))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
    }

    static String registerBody(String employeeNo, String iamAccount) {
        return """
                {"employeeNo":"%s","iamAccount":"%s","firstName":"길동","lastName":"홍",
                 "department":"영업팀","initialPassword":"Init-Pass-1234","hrAdmin":false}
                """.formatted(employeeNo, iamAccount);
    }

    Path accountsFile() {
        return mailConfig.resolve("postfix-accounts.cf");
    }

    List<String> mailboxes() throws IOException {
        return Files.exists(accountsFile())
                ? Files.readAllLines(accountsFile()).stream().map(l -> l.substring(0, l.indexOf('|'))).toList()
                : List.of();
    }

    // ---- 권한 ----

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(get("/employee-service/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_직원은_직원_목록을_볼_수_없다_403() throws Exception {
        mvc.perform(get("/employee-service/employees").with(employee())).andExpect(status().isForbidden());
    }

    @Test
    void 일반_직원은_직원을_등록할_수_없다_403() throws Exception {
        mvc.perform(post("/employee-service/employees").with(employee())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "gildong.hong")))
                .andExpect(status().isForbidden());
        then(keycloak).should(never()).ensureUser(anyString(), anyString(), anyString(), anyString());
    }

    // ---- 입력값 검증: 한국어 메시지를 ProblemDetail.detail로 ----

    @Test
    void 계정명_규칙_위반은_400과_한국어_메시지() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "Gil Dong")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("입력값 오류"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("IAM 계정명은 영문 소문자·숫자와 . _ - 만 사용하세요.")));
    }

    @Test
    void 계정명은_3자_이상() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "ab")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("IAM 계정명은 3~30자로 입력하세요.")));
    }

    @Test
    void 계정명은_30자_이하() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "a".repeat(31))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 계정명에_도메인을_넣을_수_없다() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "gildong@evil.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 사번_형식_위반은_400() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("emp-1", "gildong.hong")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("사번은 영문 대문자·숫자 3~20자로 입력하세요.")));
    }

    // ---- IAM 계정 발급 (D21) + 메일함 (D22) ----

    @Test
    void 등록하면_IAM_계정_ID는_계정명_골뱅이_도메인이고_Keycloak_username이자_이메일() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "gildong.hong")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeNo").value("EMP0100"))
                .andExpect(jsonPath("$.iamId").value("gildong.hong@pmart.test"))
                .andExpect(jsonPath("$.provisioningStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("EMPLOYEE")));

        then(keycloak).should().ensureUser("gildong.hong@pmart.test", "길동", "홍", "gildong.hong@pmart.test");
        // 인사팀이 정한 초기 비밀번호는 첫 로그인 때 바꾸도록 temporary=true
        then(keycloak).should().setPassword(anyString(), eq("Init-Pass-1234"), eq(true));
        assertThat(mailboxes()).containsExactly("gildong.hong@pmart.test");
    }

    @Test
    void 이미_발급된_사번은_409() throws Exception {
        register("EMP0100", "gildong.hong");

        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "other.name")))
                .andExpect(status().isConflict());
    }

    @Test
    void 다른_직원이_쓰는_IAM_계정명은_409이고_Keycloak을_호출하지_않는다() throws Exception {
        register("EMP0100", "gildong.hong");
        reset(keycloak);

        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0200", "gildong.hong")))
                .andExpect(status().isConflict());
        then(keycloak).should(never()).ensureUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void Keycloak_장애면_502와_FAILED_기록_후_같은_사번으로_재시도하면_이어서_발급() throws Exception {
        given(keycloak.ensureUser(anyString(), anyString(), anyString(), anyString()))
                .willThrow(new ResourceAccessException("Keycloak 연결 실패"));

        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "gildong.hong")))
                .andExpect(status().isBadGateway());
        assertThat(jdbc.sql("SELECT provisioning_status FROM employees WHERE employee_no = 'EMP0100'")
                .query(String.class).single()).isEqualTo("FAILED");
        assertThat(mailboxes()).isEmpty();

        // Keycloak 복구 후 같은 사번으로 다시 요청
        reset(keycloak);
        given(keycloak.ensureUser(anyString(), anyString(), anyString(), anyString())).willReturn("kc-recovered");
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("EMP0100", "gildong.hong")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provisioningStatus").value("COMPLETED"));
        assertThat(mailboxes()).containsExactly("gildong.hong@pmart.test");
    }

    @Test
    void 인사팀_권한으로_등록하면_HR_ADMIN_역할() throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("EMP0100", "gildong.hong").replace("\"hrAdmin\":false", "\"hrAdmin\":true")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("EMPLOYEE", "HR_ADMIN")));
    }

    // ---- 퇴사 처리 (D19) ----

    @Test
    void 퇴사_처리는_DB_INACTIVE_Keycloak_비활성화_세션_종료_Refresh_Token_폐기() throws Exception {
        register("EMP0100", "gildong.hong");
        String keycloakId = jdbc.sql("SELECT keycloak_user_id FROM employees WHERE employee_no = 'EMP0100'")
                .query(String.class).single();

        mvc.perform(post("/employee-service/employees/EMP0100/deactivate").with(hrAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        then(keycloak).should().disableAndLogout(keycloakId);
        then(sessions).should().revokeAll("EMP0100");
    }

    @Test
    void 자기_자신은_비활성화할_수_없다() throws Exception {
        mvc.perform(post("/employee-service/employees/HR000001/deactivate").with(hrAdmin()))
                .andExpect(status().isBadRequest());
        then(keycloak).should(never()).disableAndLogout(any());
    }

    @Test
    void 없는_사번_비활성화는_404() throws Exception {
        mvc.perform(post("/employee-service/employees/NOPE0001/deactivate").with(hrAdmin()))
                .andExpect(status().isNotFound());
    }

    // ---- 내부 API (/internal/**, X-Internal-Api-Key) ----

    static String upsertBody(String status) {
        return """
                {"iamId":"hr.admin@pmart.test","firstName":"인사","lastName":"테스트","department":"인사팀",
                 "status":"%s","roles":["EMPLOYEE","HR_ADMIN"],"keycloakUserId":"kc-hr"}
                """.formatted(status);
    }

    @Test
    void 내부_API는_키가_없으면_401() throws Exception {
        mvc.perform(put("/internal/employees/HR000001").contentType(MediaType.APPLICATION_JSON).content(upsertBody("ACTIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_API는_키가_틀리면_401() throws Exception {
        mvc.perform(put("/internal/employees/HR000001").header("X-Internal-Api-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(upsertBody("ACTIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_API는_HR_ADMIN_JWT만으로는_호출할_수_없다() throws Exception {
        mvc.perform(put("/internal/employees/HR000001").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(upsertBody("ACTIVE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_upsert는_재직_직원의_메일함도_만든다() throws Exception {
        // 회귀 테스트: 새 환경에서 테스트용 인사팀 계정은 이 API로 등록되며, 부팅 동기화(MailboxSync)보다 늦게 옴
        // (2026-11-12 새 환경 검증에서 메일함이 없어 IMAP 로그인이 실패했던 문제)
        mvc.perform(put("/internal/employees/HR000001").header("X-Internal-Api-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(upsertBody("ACTIVE")))
                .andExpect(status().isOk());

        assertThat(mailboxes()).containsExactly("hr.admin@pmart.test");
        mvc.perform(get("/internal/employees/by-keycloak-id/kc-hr").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNo").value("HR000001"))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.contains("EMPLOYEE", "HR_ADMIN")));
    }

    @Test
    void 내부_upsert로_INACTIVE_직원은_메일함을_만들지_않는다() throws Exception {
        mvc.perform(put("/internal/employees/HR000001").header("X-Internal-Api-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(upsertBody("INACTIVE")))
                .andExpect(status().isOk());

        assertThat(mailboxes()).isEmpty();
    }

    private void register(String employeeNo, String iamAccount) throws Exception {
        mvc.perform(post("/employee-service/employees").with(hrAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(employeeNo, iamAccount)))
                .andExpect(status().isCreated());
    }
}
