package com.example.multitenant.application.port.in.results;

import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantStatus;

/**
 * 테넌트 조회 use case 처리 결과
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record GetTenantResult(
        String tenantId,
        String status,
        String suspendReason
) {
    public static GetTenantResult from(Tenant tenant) {
        return switch (tenant.getStatus()) {
            case TenantStatus.Active a ->
                    new GetTenantResult(tenant.getId().value(), "ACTIVE", null);
            case TenantStatus.Suspended s ->
                    new GetTenantResult(tenant.getId().value(), "SUSPENDED", s.reason());
        };
    }
}
