package com.example.multitenant.application.port.out;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantDataSourcePort {
    void register(TenantId id, DataSourceSpec spec);
    boolean isRegistered(TenantId id);
    void refreshRoutingTable();
}
