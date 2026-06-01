package com.example.multitenant.application.port.in.command;

/**
 * 테넌트 정지 커맨드.
 *
 * @param tenantId 정지할 테넌트 식별자
 * @param reason   정지 사유 (로그·감사 목적으로 기록)
 * @author gihyung.lee
 * @since 2026-06-01
 */
public record SuspendTenantCommand(String tenantId, String reason) {}
