package com.example.multitenant.application.port.in.command;

/**
 * 테넌트 검증, 할당 controller -> service 명령
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record ResolveTenantCommand(
        String tenantId
) {
}
