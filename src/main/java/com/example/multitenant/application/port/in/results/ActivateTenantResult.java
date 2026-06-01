package com.example.multitenant.application.port.in.results;

import com.example.multitenant.domain.tenant.Tenant;

/**
 * 테넌트 재활성화 유즈케이스 결과.
 *
 * <p>재활성화가 성공적으로 처리되었음을 나타내는 최소 정보만 담는다.
 * @author gihyung.lee
 * @since 2026-06-01
 */
public record ActivateTenantResult(String tenantId, String status) {
    public static ActivateTenantResult from(Tenant tenant) {
        return new ActivateTenantResult(
                tenant.getId().value(),
                tenant.getStatus().code()
        );
    }
}
