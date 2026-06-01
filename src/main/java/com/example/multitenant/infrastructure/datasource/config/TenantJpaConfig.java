package com.example.multitenant.infrastructure.datasource.config;

import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Properties;

/**
 * 테넌트 DataSource JPA 구성.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>데모 메시지({@code demo_message} 테이블)를 관리하는 {@code tenantEntityManagerFactory} 생성</li>
 *   <li>스캔 대상: {@code demo.infrastructure} 패키지 — {@code DemoMessageJpaEntity}, {@code DemoMessageRepository}</li>
 *   <li>DDL: {@code none} — 테넌트 DB 스키마는 {@link com.example.multitenant.infrastructure.datasource.TenantSchemaInitializer}가 테넌트 등록 시점에 초기화</li>
 * </ul>
 *
 * <h2>LazyConnectionDataSourceProxy 사용 이유</h2>
 * {@code RoutingDataSource}는 기동 시점에 테넌트 DataSource 가 없는 빈 상태다.
 * Hibernate는 {@code SessionFactory} 초기화 과정에서 {@code DataSource.getConnection()}을
 * 호출할 수 있는데, 이 경우 테넌트 컨텍스트가 없어 {@code TenantContextMissingException}이 발생한다.
 * {@link LazyConnectionDataSourceProxy}로 감싸면 실제 JDBC 문(Statement) 실행 직전까지
 * 커넥션 획득이 지연되므로, 기동 시 예외 없이 EMF 를 생성할 수 있다.
 *
 * <h2>다이얼렉트 명시 이유</h2>
 * 자동 감지를 위한 JDBC 메타데이터 조회 없이 빠르게 EMF 를 초기화하기 위해
 * {@code hibernate.dialect}를 {@code PostgreSQLDialect}로 고정한다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@Configuration
@EnableJpaRepositories(
        basePackages           = "com.example.multitenant.demo.infrastructure",
        entityManagerFactoryRef = "tenantEntityManagerFactory",
        transactionManagerRef  = "tenantTransactionManager"
)
public class TenantJpaConfig {

    /**
     * 테넌트 DB용 EntityManagerFactory.
     *
     * <p>RoutingDataSource 를 LazyConnectionDataSourceProxy 로 감싸 기동 시 커넥션 획득을 방지한다.
     * 다이얼렉트를 명시하여 JDBC 메타데이터 조회를 생략한다.
     * DDL 은 {@code none} — 스키마 생성은 {@code TenantSchemaInitializer} 에 위임한다.
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean tenantEntityManagerFactory(
            RoutingDataSource routingDataSource) {

        // LazyConnectionDataSourceProxy: 실제 SQL 실행 전까지 커넥션 획득 지연
        LazyConnectionDataSourceProxy lazyDs = new LazyConnectionDataSourceProxy(routingDataSource);

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(lazyDs);
        em.setPackagesToScan("com.example.multitenant.demo.infrastructure");
        em.setPersistenceUnitName("tenant");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Properties props = new Properties();
        // 다이얼렉트 고정 — 기동 시 JDBC 메타데이터 조회 방지
        props.setProperty("hibernate.dialect",      "org.hibernate.dialect.PostgreSQLDialect");
        props.setProperty("hibernate.hbm2ddl.auto", "none");
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
     * 테넌트 DB용 트랜잭션 매니저.
     *
     * <p>테넌트 DB 관련 서비스는 {@code @Transactional("tenantTransactionManager")} 를 지정한다.
     */
    @Bean
    public PlatformTransactionManager tenantTransactionManager(
            @Qualifier("tenantEntityManagerFactory") EntityManagerFactory tenantEntityManagerFactory) {

        return new JpaTransactionManager(tenantEntityManagerFactory);
    }
}
