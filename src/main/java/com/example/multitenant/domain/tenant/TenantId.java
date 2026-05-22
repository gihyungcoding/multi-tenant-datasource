package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.utils.ValidateUtil;

/**
 * 멀티 테넌트 식별자
 * @author gihyung.lee
 * @since 2026-05-21
 */
public record TenantId(String value) {

    public TenantId {
        ValidateUtil.validate("value", value);
    }

    @Override
    public String toString() {
        return value;
    }
}
