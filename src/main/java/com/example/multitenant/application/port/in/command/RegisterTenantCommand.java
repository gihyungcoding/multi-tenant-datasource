package com.example.multitenant.application.port.in.command;

import java.util.List;

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
        List<SlaveSpec> slaves  // 빈 리스트면 slave 미구성
) {
    /** slave 없는 master 단독 구성 — 하위 호환 생성자 */
    public RegisterTenantCommand(String tenantId, String url, String username, String password) {
        this(tenantId, url, username, password, List.of());
    }

    public record SlaveSpec(String url, String username, String password) {}
}
