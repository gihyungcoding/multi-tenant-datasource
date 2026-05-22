package com.example.multitenant.interfaces.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 테넌트 등록 요청 데이터
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record RegisterTenantRequest(
        @NotBlank String tenantId,
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password
) {}
