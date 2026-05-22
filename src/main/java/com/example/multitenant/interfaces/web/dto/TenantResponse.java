package com.example.multitenant.interfaces.web.dto;

import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;

/**
 * 테넌트 등록 응답 결과
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record TenantResponse(
        String tenantId,
        String status,
        String suspendReason
) {
    public static TenantResponse from(RegisterTenantResult tenant) {
        return new TenantResponse(tenant.tenantId(), tenant.status(), null);
    }

    public static TenantResponse from(GetTenantResult tenant) {
        return new TenantResponse(tenant.tenantId(), tenant.status(), tenant.suspendReason());
    }
}
