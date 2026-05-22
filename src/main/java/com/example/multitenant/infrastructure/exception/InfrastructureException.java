package com.example.multitenant.infrastructure.exception;

import com.example.multitenant.domain.exception.ErrorCode;

/**
 * Infrastructure 계층 상위 에러
 * @author gihyung.lee
 * @since 2026-05-22
 */
public abstract class InfrastructureException extends RuntimeException {

    private final ErrorCode errorCode;

    protected InfrastructureException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected InfrastructureException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public static InfrastructureException of(ErrorCode errorCode) {
        return new InfrastructureException(errorCode) {};
    }
}
