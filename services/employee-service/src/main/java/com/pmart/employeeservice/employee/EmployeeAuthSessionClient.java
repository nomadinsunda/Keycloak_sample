package com.pmart.employeeservice.employee;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.pmart.employeeservice.config.PmartProperties;

/** Employee Auth Service 내부 API 클라이언트 (내부 네트워크 직접 호출, 내부 API 키 사용) */
@Component
public class EmployeeAuthSessionClient {

    private final RestClient rest;

    public EmployeeAuthSessionClient(PmartProperties props) {
        this.rest = RestClient.builder()
                .baseUrl(props.employeeAuth().baseUrl())
                .defaultHeader("X-Internal-Api-Key", props.internal().apiKey())
                .build();
    }

    /** 이 직원의 모든 PMart Refresh Token 폐기 */
    public void revokeAll(String employeeNo) {
        rest.post()
                .uri("/internal/sessions/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("employeeNo", employeeNo))
                .retrieve()
                .toBodilessEntity();
    }
}
