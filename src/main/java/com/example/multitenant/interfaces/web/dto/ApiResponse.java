package com.example.multitenant.interfaces.web.dto;

/**
 * API 응답 표준
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error
) {
    // 성공
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    // 성공 (데이터 없음)
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    // 실패
    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
