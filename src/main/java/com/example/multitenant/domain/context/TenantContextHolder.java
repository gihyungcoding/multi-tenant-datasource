package com.example.multitenant.domain.context;

import com.example.multitenant.domain.tenant.TenantId;

/**
 * 테넌트 도메인 포트
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantContextHolder {
    void setTenant(TenantId tenantId);
    TenantId getTenant();
    boolean hasTenant();
    void clear();
}
