package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class InvalidTenantStatusException extends DomainException {

    private final String code;

    public InvalidTenantStatusException(String code) {
        super(ErrorCode.INVALID_ENUM_VALUE, "올바르지 않은 TenantStatus 코드입니다");
        this.code = code;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("code", code);
    }
}
