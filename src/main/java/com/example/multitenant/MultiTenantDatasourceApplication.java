package com.example.multitenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;

/**
 * <h2>JPA 자동 구성 제외</h2>
 * 마스터 DB(테넌트 메타데이터)와 테넌트 DB(데모 메시지)를 독립된 EntityManagerFactory로
 * 관리하기 위해 Boot 의 단일 EMF 자동 구성을 비활성화한다.
 *
 * <ul>
 *   <li>{@code HibernateJpaAutoConfiguration} — 단일 {@code EntityManagerFactory} 생성을 차단</li>
 *   <li>{@code DataJpaRepositoriesAutoConfiguration} — 단일 EMF 기반 Repository 스캔을 차단</li>
 * </ul>
 *
 * 대신 {@link com.example.multitenant.infrastructure.datasource.config.MasterJpaConfig}와
 * {@link com.example.multitenant.infrastructure.datasource.config.TenantJpaConfig}에서
 * 각 DataSource에 대한 EMF·TransactionManager·Repository 를 명시적으로 구성한다.
 */
@SpringBootApplication(exclude = {
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
})
public class MultiTenantDatasourceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MultiTenantDatasourceApplication.class, args);
	}

}
