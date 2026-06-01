package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * 이미 활성화된 테넌트를 다시 활성화하려 할 때 발생하는 예외 (409 Conflict).
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
public class TenantAlreadyActiveException extends DomainException {

    private final String tenantId;

    public TenantAlreadyActiveException(TenantId tenantId) {
        super(ErrorCode.TENANT_ALREADY_ACTIVE);
        this.tenantId = tenantId.value();
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("tenantId", tenantId);
    }
}
