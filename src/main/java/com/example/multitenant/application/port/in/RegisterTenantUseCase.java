package com.example.multitenant.application.port.in;

import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;

/**
 * 테넌트 등록 유즈 케이스
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface RegisterTenantUseCase {
    RegisterTenantResult register(RegisterTenantCommand command);
}
