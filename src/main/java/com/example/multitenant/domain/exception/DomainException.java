package com.example.multitenant.domain.exception;

import java.util.Collections;
import java.util.Map;

/**
 * 도메인 에러
 * @author gihyung.lee
 * @since 2026-05-22
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DomainException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 추가 데이터가 필요한 예외는 이 메서드를 오버라이드.
     * 기본은 빈 맵 반환 — 핸들러에서 null 체크 불필요.
     */
    public Map<String, Object> getDetails() {
        return Collections.emptyMap();
    }

    // 단순 예외 인라인 생성
    public static DomainException of(ErrorCode errorCode) {
        return new DomainException(errorCode) {};
    }

    public static DomainException of(ErrorCode errorCode, String message) {
        return new DomainException(errorCode, message) {};
    }
}
