package com.example.multitenant.application.port.in.command;

/**
 * 테넌트 생성 controller -> service 명령
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record RegisterTenantCommand(
        String tenantId,
        String url,
        String username,
        String password
) {
}
