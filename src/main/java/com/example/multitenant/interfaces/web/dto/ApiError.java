package com.example.multitenant.interfaces.web.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API 에러 표준 응답
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record ApiError(
        String code,
        String message,
        LocalDateTime timestamp,
        Map<String, Object> details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, LocalDateTime.now(), null);
    }

    public static ApiError of (String code, String message, Map<String, Object> details) {
        return new ApiError(code, message, LocalDateTime.now(), details);
    }
}
