package com.example.multitenant.domain.tenant;

/**
 * 테넌트 상태
 * Active : 운영
 * Suspended : 정지
 * @author gihyung.lee
 * @since 2026-05-21
 */
public sealed interface TenantStatus
    permits TenantStatus.Active, TenantStatus.Suspended {

    record Active() implements TenantStatus {}
    record Suspended(String reason) implements TenantStatus {}
}
