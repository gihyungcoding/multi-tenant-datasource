package com.example.multitenant.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 정의
 * @author gihyung.lee
 * @since 2026-05-22
 */
public enum ErrorCode {
    // Tenant
    TENANT_NOT_FOUND        (HttpStatus.NOT_FOUND,            "존재하지 않는 테넌트입니다"),
    TENANT_ALREADY_EXISTS   (HttpStatus.CONFLICT,             "이미 존재하는 테넌트입니다"),
    TENANT_SUSPENDED        (HttpStatus.FORBIDDEN,            "정지된 테넌트입니다"),
    TENANT_CONTEXT_MISSING  (HttpStatus.BAD_REQUEST,          "테넌트 컨텍스트가 없습니다. TenantInterceptor를 확인하세요."),
    INVALID_ESSENTIAL_ARGUMENT       (HttpStatus.BAD_REQUEST, "필수 인자가 올바르지 않습니다"),

    // 공통
    INVALID_INPUT           (HttpStatus.BAD_REQUEST,          "잘못된 입력값입니다"),
    INTERNAL_SERVER_ERROR   (HttpStatus.INTERNAL_SERVER_ERROR,"서버 내부 오류가 발생했습니다");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getDefaultMessage() { return defaultMessage; }
}
