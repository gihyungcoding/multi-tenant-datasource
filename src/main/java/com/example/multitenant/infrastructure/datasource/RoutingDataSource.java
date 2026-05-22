package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.exception.TenantContextMissingException;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 테넌트별 DataSource 라우팅 관리
 * @author gihyung.lee
 * @since 2026-05-21
 */
public class RoutingDataSource extends AbstractRoutingDataSource {
    private final TenantContextHolder contextHolder;

    public RoutingDataSource(TenantContextHolder contextHolder) {
        this.contextHolder = contextHolder;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (!contextHolder.hasTenant()) {
            throw new TenantContextMissingException();
        }
        return contextHolder.getTenant().value();
    }

    public void refresh(Map<String, DataSource> dataSourceMap) {
        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
