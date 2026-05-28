package com.example.multitenant.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 테스트 베이스 클래스.
 *
 * Singleton 컨테이너 패턴 — static block 에서 직접 start()하여 여러 테스트 클래스가
 * 동일 컨테이너를 공유한다. @Testcontainers/@Container 는 테스트 클래스 종료 시 컨테이너를
 * stop하므로 멀티 클래스 환경에서 사용하지 않는다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer masterDb =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("master")
                    .withUsername("master")
                    .withPassword("master");

    static final PostgreSQLContainer tenantADb =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tenant_a")
                    .withUsername("tenant_a")
                    .withPassword("tenant_a");

    static final PostgreSQLContainer tenantBDb =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tenant_b")
                    .withUsername("tenant_b")
                    .withPassword("tenant_b");

    static {
        masterDb.start();
        tenantADb.start();
        tenantBDb.start();
    }

    @DynamicPropertySource
    static void masterDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.master.url",      masterDb::getJdbcUrl);
        registry.add("spring.datasource.master.username", masterDb::getUsername);
        registry.add("spring.datasource.master.password", masterDb::getPassword);
    }

    protected String getTenantAUrl() { return tenantADb.getJdbcUrl(); }
    protected String getTenantBUrl() { return tenantBDb.getJdbcUrl(); }
}
