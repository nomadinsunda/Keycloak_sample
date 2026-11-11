package com.pmart.sampleapi;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PMart JWT 검증 확인용 샘플 업무 API */
@RestController
@RequestMapping("/sample-api")
public class SampleController {

    /** 모든 직원 */
    @GetMapping("/hello")
    public Map<String, Object> hello(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "message", "PMart JWT 검증 성공",
                "employeeNo", jwt.getSubject(),
                "roles", jwt.getClaimAsStringList("roles"));
    }

    /** 인사팀(HR_ADMIN)만 */
    @GetMapping("/hr-only")
    @PreAuthorize("hasRole('HR_ADMIN')")
    public Map<String, Object> hrOnly(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("message", "HR_ADMIN 권한 확인 성공", "employeeNo", jwt.getSubject());
    }
}
