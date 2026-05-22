package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class TenantNotFoundException extends DomainException {

    private final String tenantId;

    public TenantNotFoundException(TenantId tenantId) {
        super(ErrorCode.TENANT_NOT_FOUND);
        this.tenantId = tenantId.value();
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("tenantId", tenantId);
    }
}
