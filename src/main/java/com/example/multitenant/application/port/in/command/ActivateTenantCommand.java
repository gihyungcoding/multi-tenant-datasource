package com.example.multitenant.application.port.in.command;

/**
 * 테넌트 재활성화 커맨드.
 *
 * @param tenantId 재활성화할 테넌트 식별자
 * @author gihyung.lee
 * @since 2026-06-01
 */
public record ActivateTenantCommand(String tenantId) {}
