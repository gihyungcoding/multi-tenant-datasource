package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;

/**
 * 테넌트 상태
 * Active : 운영
 * Suspended : 정지
 * @author gihyung.lee
 * @since 2026-05-21
 */
public sealed interface TenantStatus
    permits TenantStatus.Active, TenantStatus.Suspended {

    String ACTIVE_CODE    = "ACTIVE";
    String SUSPENDED_CODE = "SUSPENDED";

    String code();

    record Active() implements TenantStatus {
        @Override
        public String code() {
            return ACTIVE_CODE;
        }
    }

    record Suspended(String reason) implements TenantStatus {
        @Override
        public String code() {
            return SUSPENDED_CODE;
        }
    }

    static TenantStatus from(String code, String reason) {
        return switch (code) {
            case ACTIVE_CODE -> new Active();
            case SUSPENDED_CODE -> new Suspended(reason);
            default -> throw new InvalidTenantStatusException(code);
        };
    }
}
