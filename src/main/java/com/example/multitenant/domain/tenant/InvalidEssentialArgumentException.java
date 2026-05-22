package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class InvalidEssentialArgumentException extends DomainException {

    private final String field;

    private final String value;

    public InvalidEssentialArgumentException(String field, String value) {
        super(ErrorCode.INVALID_ESSENTIAL_ARGUMENT);
        this.field = field;
        this.value = value;
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of(field, value);
    }
}
