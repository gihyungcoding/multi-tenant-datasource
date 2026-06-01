package com.example.multitenant.application.port.out;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantDataSourcePort {
    void register(TenantId id, DataSourceSpec spec);
    /** 라우팅 테이블에서 제거하고 HikariCP 풀을 종료한다. */
    void deregister(TenantId id);
    boolean isRegistered(TenantId id);
    void refreshRoutingTable();
}
