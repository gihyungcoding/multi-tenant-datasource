package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import org.springframework.stereotype.Component;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceAdapter implements TenantDataSourcePort {

    private final TenantDataSourceRegistry registry;
    private final RoutingDataSource routingDataSource;

    public TenantDataSourceAdapter(TenantDataSourceRegistry registry,
                                   RoutingDataSource routingDataSource) {
        this.registry = registry;
        this.routingDataSource = routingDataSource;
    }

    @Override
    public void register(TenantId tenantId, DataSourceSpec spec) {
        registry.register(tenantId, spec);
        routingDataSource.refresh(registry.snapshot());
    }

    @Override
    public boolean isRegistered(TenantId tenantId) {
        return registry.isRegistered(tenantId);
    }

    @Override
    public void refreshRoutingTable() {
        routingDataSource.refresh(registry.snapshot());
    }

}
