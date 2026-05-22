package com.example.multitenant.infrastructure.datasource.config;

import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * DataSource 환경 설정
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.master")
    public DataSourceProperties masterDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary // master datasource를 기본값으로 사용
    public DataSource masterDataSource() {
        return masterDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean
    public RoutingDataSource routingDataSource(TenantContextHolder contextHolder) {
        return new RoutingDataSource(contextHolder);
    }
}
