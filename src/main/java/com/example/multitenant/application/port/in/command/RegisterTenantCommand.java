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
        String password,
        SlaveSpec slave  // null 이면 slave 미구성
) {
    public RegisterTenantCommand(String tenantId, String url, String username, String password) {
        this(tenantId, url, username, password, null);
    }

    public record SlaveSpec(String url, String username, String password) {}
}
