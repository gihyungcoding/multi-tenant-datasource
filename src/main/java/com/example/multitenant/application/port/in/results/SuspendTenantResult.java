package com.example.multitenant.application.port.in.results;

import com.example.multitenant.domain.tenant.Tenant;

/**
 * 테넌트 정지 유즈케이스 결과.
 *
 * <p>정지가 성공적으로 처리되었음을 나타내는 최소 정보만 담는다.
 * 정지 사유는 요청 측이 이미 알고 있으므로 포함하지 않는다.
 * @author gihyung.lee
 * @since 2026-06-01
 */
public record SuspendTenantResult(String tenantId, String status) {
    public static SuspendTenantResult from(Tenant tenant) {
        return new SuspendTenantResult(
                tenant.getId().value(),
                tenant.getStatus().code()
        );
    }
}
