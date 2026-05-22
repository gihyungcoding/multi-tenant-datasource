package com.example.multitenant.application.port.in;

import com.example.multitenant.application.port.in.command.GetTenantCommand;
import com.example.multitenant.application.port.in.results.GetTenantResult;

/**
 * 테넌트 조회 유즈케이스
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface GetTenantUseCase {
    GetTenantResult getById(GetTenantCommand command);
}
