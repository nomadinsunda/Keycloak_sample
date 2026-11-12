package com.pmart.employeeservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너. 이미지 버전은 compose.yaml의 postgres 서비스와 같게 맞춥니다.
 * {@link ServiceConnection}이 spring.datasource.*를 컨테이너 주소·계정으로 대신 설정합니다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18.6-alpine");
    }
}
