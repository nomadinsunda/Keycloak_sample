package com.pmart.employeeservice.employee;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

public record Employee(
        Long id,
        String employeeNo,
        String iamId,
        String firstName,
        String lastName,
        String email,
        String department,
        String status,
        String roles,
        String keycloakUserId,
        String provisioningStatus,
        String provisioningError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public List<String> roleList() {
        return Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
