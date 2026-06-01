package com.example.multitenant.application.port.in;

import com.example.multitenant.application.port.in.command.ActivateTenantCommand;
import com.example.multitenant.application.port.in.results.ActivateTenantResult;

/**
 * 테넌트 활성화 유즈케이스
 * @author gihyung.lee
 * @since 2026-06-01
 */
public interface ActivateTenantUseCase {
    ActivateTenantResult activate(ActivateTenantCommand command);
}
