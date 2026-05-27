package com.example.multitenant.application.port.in.results;

import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantStatus;

/**
 * 테넌트 생성 use case 처리 결과
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record RegisterTenantResult(
        String tenantId,
        String status
) {
    public static RegisterTenantResult from(Tenant tenant) {
        return new RegisterTenantResult(tenant.getId().value(), tenant.getStatus().code());
    }
}
