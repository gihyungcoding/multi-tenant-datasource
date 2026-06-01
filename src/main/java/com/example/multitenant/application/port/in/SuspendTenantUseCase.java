package com.example.multitenant.application.port.in;

import com.example.multitenant.application.port.in.command.SuspendTenantCommand;
import com.example.multitenant.application.port.in.results.SuspendTenantResult;

/**
 * 테넌트 정지 유즈케이스
 * @author gihyung.lee
 * @since 2026-06-01
 */
public interface SuspendTenantUseCase {
    SuspendTenantResult suspend(SuspendTenantCommand command);
}
