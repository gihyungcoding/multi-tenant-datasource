package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import org.springframework.stereotype.Component;

/**
 * Tenant ThreadLocal 관리
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantContextHolderImpl implements TenantContextHolder {

    private static final ThreadLocal<TenantId> CURRENT_TENANT = new ThreadLocal<>();

    @Override
    public void setTenant(TenantId tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    @Override
    public TenantId getTenant() {
        return CURRENT_TENANT.get();
    }

    @Override
    public boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }

    @Override
    public void clear() {
        CURRENT_TENANT.remove();
    }
}
