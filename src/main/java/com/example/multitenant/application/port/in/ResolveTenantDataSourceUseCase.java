package com.example.multitenant.application.port.in;

import com.example.multitenant.application.port.in.command.ResolveTenantCommand;

/**
 * 테넌트 검증, 할당 유즈 케이스
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface ResolveTenantDataSourceUseCase {
    // 컨텍스트 세팅 + 유효성 검증
    void resolve(ResolveTenantCommand command);
}
