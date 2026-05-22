package com.example.multitenant.application.port.in.command;

/**
 * 조회 controller -> service 명령
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record GetTenantCommand(
        String tenantId
) {
}
