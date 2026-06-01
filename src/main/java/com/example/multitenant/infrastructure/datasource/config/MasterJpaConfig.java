package com.example.multitenant.infrastructure.datasource.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 마스터 DataSource JPA 구성.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>테넌트 메타데이터({@code tenant} 테이블)를 관리하는 {@code masterEntityManagerFactory} 생성</li>
 *   <li>스캔 대상: {@code infrastructure.persistence} 패키지 — {@code TenantJpaEntity}, {@code TenantJpaRepository}</li>
 *   <li>스키마: 기동 시 {@code db/master/schema.sql}로 초기화 후 Hibernate {@code validate}로 정합성 확인</li>
 * </ul>
 *
 * <h2>스키마 초기화 전략</h2>
 * <ol>
 *   <li>{@link ResourceDatabasePopulator}가 {@code db/master/schema.sql}을 실행하여
 *       {@code tenant} 테이블과 {@code idx_tenant_status} 인덱스를 생성한다.
 *       {@code IF NOT EXISTS} 구문이므로 재기동 시에도 멱등하다.</li>
 *   <li>이후 Hibernate {@code validate}가 엔티티 매핑과 실제 스키마의 일치 여부를 검증한다.
 *       불일치 시 애플리케이션이 기동 실패하여 스키마 드리프트를 즉시 감지한다.</li>
 * </ol>
 *
 * <h2>@Primary 선언 이유</h2>
 * {@code @Transactional} 을 한정자 없이 사용하는 서비스(예: {@code RegisterTenantService})가
 * 자동으로 마스터 TM을 사용하도록 한다.
 * 테넌트 DB 관련 서비스({@code DemoService})는 {@code @Transactional("tenantTransactionManager")}
 * 로 TM을 명시한다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.multitenant.infrastructure.persistence",
        entityManagerFactoryRef = "masterEntityManagerFactory",
        transactionManagerRef   = "masterTransactionManager"
)
public class MasterJpaConfig {

    private static final ResourceDatabasePopulator MASTER_SCHEMA_POPULATOR =
            new ResourceDatabasePopulator(new ClassPathResource("db/master/schema.sql"));

    /**
     * 마스터 DB용 EntityManagerFactory.
     *
     * <p>EMF 생성 전 {@code db/master/schema.sql}을 실행하여 스키마를 초기화한다.
     * Hibernate는 {@code validate} 모드로 엔티티와 실제 스키마의 정합성만 확인한다.
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean masterEntityManagerFactory(
            @Qualifier("masterDataSource") DataSource masterDataSource) {

        // 1단계: SQL 파일로 스키마 초기화 (IF NOT EXISTS — 멱등)
        DatabasePopulatorUtils.execute(MASTER_SCHEMA_POPULATOR, masterDataSource);

        // 2단계: EMF 생성 — validate 로 엔티티↔스키마 정합성 검증
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(masterDataSource);
        em.setPackagesToScan("com.example.multitenant.infrastructure.persistence");
        em.setPersistenceUnitName("master");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Properties props = new Properties();
        props.setProperty("hibernate.hbm2ddl.auto", "validate");
        props.setProperty("hibernate.show_sql",     "true");
        props.setProperty("hibernate.format_sql",   "true");
        // camelCase 필드 → snake_case 컬럼 자동 변환 (Spring Boot 자동 구성 기본값과 동일)
        props.setProperty("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        props.setProperty("hibernate.implicit_naming_strategy",
                "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
        em.setJpaProperties(props);

        return em;
    }

    /**
     * 마스터 DB용 트랜잭션 매니저.
     *
     * <p>{@code @Primary} 이므로 {@code @Transactional} 한정자 미지정 시 이 TM이 선택된다.
     */
    @Bean
    @Primary
    public PlatformTransactionManager masterTransactionManager(
            @Qualifier("masterEntityManagerFactory") EntityManagerFactory masterEntityManagerFactory) {

        return new JpaTransactionManager(masterEntityManagerFactory);
    }
}
