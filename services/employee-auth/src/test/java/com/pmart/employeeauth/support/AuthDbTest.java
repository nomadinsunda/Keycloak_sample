package com.pmart.employeeauth.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

import com.pmart.employeeauth.login.LoginTransactionRepository;
import com.pmart.employeeauth.login.MobileTicketRepository;
import com.pmart.employeeauth.token.PmartTokenService;
import com.pmart.employeeauth.token.RefreshTokenService;
import com.pmart.employeeauth.token.SigningKeyService;

/**
 * Employee Auth Service의 DB·토큰 계층 테스트용 슬라이스.
 *
 * <p>왜 @SpringBootTest가 아닌가: 전체 컨텍스트를 띄우면 부팅 시 테스트용 인사팀 계정을 만드는
 * {@code HrTestAccountBootstrap}이 Keycloak을 호출하며 최대 150초 재시도합니다. 여기서는 PostgreSQL(Testcontainers)과
 * 검증 대상 빈만 올립니다. Flyway 마이그레이션은 @JdbcTest가 실행하므로 운영과 같은 스키마로 테스트합니다.
 *
 * <p>모든 테스트 클래스가 같은 구성을 쓰므로 Spring 테스트 컨텍스트(와 PostgreSQL 컨테이너)는 한 번만 만들어집니다.
 * 각 테스트는 @JdbcTest의 트랜잭션 안에서 실행되고 끝나면 롤백됩니다.
 * 주의: 트랜잭션 안에서는 PostgreSQL {@code now()}가 트랜잭션 시작 시각으로 고정됩니다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JdbcTest
// @ServiceConnection으로 받은 PostgreSQL을 내장 DB로 바꾸지 않도록 명시 (H2 등 다른 DB 사용 금지)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, TestAuthConfiguration.class,
        SigningKeyService.class, PmartTokenService.class, RefreshTokenService.class,
        LoginTransactionRepository.class, MobileTicketRepository.class })
public @interface AuthDbTest {
}
