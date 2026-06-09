package com.example.multitenant.interfaces.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 테넌트 등록 요청 데이터.
 *
 * <p>{@code slave} 는 옵션이다. 미설정 시 master 단독 운영 모드로 등록된다.
 * slave 설정 시 {@code @Transactional(readOnly = true)} 트랜잭션이 자동으로 slave DB 로 라우팅된다.
 *
 * @author gihyung.lee
 * @since 2026-05-22
 */
public record RegisterTenantRequest(
        @NotBlank String tenantId,
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        SlaveSpec slave
) {
    public record SlaveSpec(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password
    ) {}
}
