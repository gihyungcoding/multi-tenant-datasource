package com.example.multitenant.interfaces.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 테넌트 정지 요청 데이터.
 *
 * @param reason 정지 사유 (로그·감사 목적으로 필수)
 * @author gihyung.lee
 * @since 2026-06-01
 */
public record SuspendTenantRequest(
        @NotBlank(message = "정지 사유는 필수입니다") String reason
) {}
