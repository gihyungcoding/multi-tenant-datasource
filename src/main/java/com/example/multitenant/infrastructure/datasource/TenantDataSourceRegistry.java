package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 멀티테넌트 DataSource 등록기
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceRegistry {

    private final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

    public void register(TenantId tenantId, DataSourceSpec spec) {
        dataSourceMap.put(tenantId.value(), createDataSource(tenantId, spec));
    }

    public boolean isRegistered(TenantId tenantId) {
        return dataSourceMap.containsKey(tenantId.value());
    }

    // RoutingDataSource에 전달할 String 키 맵
    public Map<String, DataSource> snapshot() {
        return Map.copyOf(dataSourceMap);
    }

    private HikariDataSource createDataSource(TenantId tenantId, DataSourceSpec spec) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.url());
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setPoolName("HikariPool-" + tenantId.value());
        return new HikariDataSource(config);
    }
}
