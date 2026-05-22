package com.example.multitenant.interfaces.web;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;
import com.example.multitenant.domain.tenant.TenantSuspendedException;
import com.example.multitenant.interfaces.web.dto.ApiError;
import com.example.multitenant.interfaces.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * RestAPI 전역 에러 처리
 * @author gihyung.lee
 * @since 2026-05-22
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 전체 도메인 예외 처리
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException e) {
        log.warn("도메인 예외: {}", e.getMessage());

        ApiError error = ApiError.of(
                e.getErrorCode().name(),
                e.getMessage(),
                e.getDetails()  // 추가 데이터
        );

        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.fail(error));
    }

    // @Valid 유효성 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse(ErrorCode.INVALID_INPUT.getDefaultMessage());

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(
                        ApiError.of(ErrorCode.INVALID_INPUT.name(), message)));
    }

    // 그 외 예상치 못한 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("예상치 못한 예외 발생", e);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(
                        ApiError.of(code.name(), code.getDefaultMessage())));
    }
}
