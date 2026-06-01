package com.example.multitenant.infrastructure.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 테넌트 DB 스키마 초기화기.
 *
 * <h2>배경</h2>
 * {@link com.example.multitenant.infrastructure.datasource.config.TenantJpaConfig}의
 * {@code EntityManagerFactory}는 {@code hbm2ddl.auto=none}으로 설정되어 있다.
 * 기동 시 {@code RoutingDataSource}에 등록된 테넌트가 없으므로 Hibernate 자동 DDL을 사용할 수 없기 때문이다.
 *
 * <h2>동작</h2>
 * {@link TenantDataSourceAdapter#register} 가 새 테넌트 DataSource를 생성한 직후 호출된다.
 * DDL 내용은 {@code db/tenant/schema.sql} 에 관리하며, {@code IF NOT EXISTS} 구문 덕분에
 * 재기동 시 이미 존재하는 스키마에 대해서도 멱등(idempotent)하게 동작한다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@Component
public class TenantSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaInitializer.class);

    private static final ResourceDatabasePopulator POPULATOR =
            new ResourceDatabasePopulator(new ClassPathResource("db/tenant/schema.sql"));

    /**
     * 주어진 DataSource 에 테넌트 스키마를 생성한다.
     *
     * <p>커넥션 획득·해제, {@link java.sql.SQLException} → {@link org.springframework.dao.DataAccessException}
     * 변환은 {@link DatabasePopulatorUtils} 가 처리한다.
     *
     * @param dataSource 초기화할 테넌트 DataSource
     */
    public void initialize(DataSource dataSource) {
        DatabasePopulatorUtils.execute(POPULATOR, dataSource);
        log.info("테넌트 스키마 초기화 완료");
    }
}
