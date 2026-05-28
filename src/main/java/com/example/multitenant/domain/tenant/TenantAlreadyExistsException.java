package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Collections;
import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class TenantAlreadyExistsException extends DomainException {

    private final String tenantId;

    public TenantAlreadyExistsException(TenantId tenantId) {
        super(ErrorCode.TENANT_ALREADY_EXISTS, "이미 존재하는 테넌트입니다: " + tenantId.value());
        this.tenantId = tenantId.value();
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("tenantId", tenantId);
    }
}
