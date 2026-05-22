package com.example.multitenant.domain.utils;

import com.example.multitenant.domain.tenant.InvalidEssentialArgumentException;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class ValidateUtil {
    public static void validate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEssentialArgumentException(field, value);
        }
    }
}
