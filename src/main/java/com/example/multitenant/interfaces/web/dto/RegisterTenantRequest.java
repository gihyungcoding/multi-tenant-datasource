package com.example.multitenant.interfaces.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 테넌트 등록 요청 데이터.
 *
 * <p>{@code slaves} 는 옵션이다. null 또는 빈 배열이면 master 단독 운영 모드로 등록된다.
 * slave 를 하나 이상 설정하면 {@code @Transactional(readOnly = true)} 트랜잭션이
 * round-robin 으로 slave 중 하나로 자동 라우팅된다.
 *
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record RegisterTenantRequest(
        @NotBlank String tenantId,
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        @Valid List<SlaveSpec> slaves
) {
    public record SlaveSpec(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password
    ) {}
}
