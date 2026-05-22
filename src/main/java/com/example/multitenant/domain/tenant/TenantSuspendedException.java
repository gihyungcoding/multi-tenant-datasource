package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class TenantSuspendedException extends DomainException {

    private final String tenantId;
    private final String reason;

    public TenantSuspendedException(TenantId tenantId, String reason) {
        super(ErrorCode.TENANT_SUSPENDED);
        this.tenantId = tenantId.value();
        this.reason = reason;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of(
                "tenantId", tenantId,
                "reason", reason
        );
    }
}
