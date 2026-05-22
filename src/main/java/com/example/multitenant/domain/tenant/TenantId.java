package com.example.multitenant.domain.tenant;

/**
 * 멀티 테넌트 식별자
 * @author gihyung.lee
 * @since 2026-05-21
 */
public record TenantId(String value) {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TenantId must not be empty");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
