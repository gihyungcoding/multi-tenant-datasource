package com.example.multitenant.infrastructure.exception;

import com.example.multitenant.domain.exception.ErrorCode;

/**
 * @author gihyung.lee
 * @since 2026-05-22
 */
public class TenantContextMissingException extends InfrastructureException {

    public TenantContextMissingException() {
        super(ErrorCode.TENANT_CONTEXT_MISSING);
    }
}
