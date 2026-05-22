package com.example.multitenant.domain.tenant;

import java.time.LocalDateTime;

/**
 * 테넌트 애그리거트 루트
 * @author gihyung.lee
 * @since 2026-05-21
 */
public class Tenant {

    private final TenantId id;
    private final DataSourceSpec dataSourceSpec;
    private TenantStatus status;
    private LocalDateTime createdAt;

    private Tenant(TenantId id, DataSourceSpec dataSourceSpec,
                   TenantStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.dataSourceSpec = dataSourceSpec;
        this.status = status;
        this.createdAt = createdAt;
    }

    public TenantId getId() {
        return id;
    }

    public DataSourceSpec getDataSourceSpec() {
        return dataSourceSpec;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 생성 팩토리 메서드
    public static Tenant create(TenantId id, DataSourceSpec spec) {
        return new Tenant(id, spec, new TenantStatus.Active(), LocalDateTime.now());
    }

    // 정지 상태로 변경
    public void suspend(String reason) {
        if (this.status instanceof TenantStatus.Suspended) {
            throw new IllegalStateException("이미 정지된 테넌트입니다: " + id);
        }
        this.status = new TenantStatus.Suspended(reason);
    }

    public void activate() {
        this.status = new TenantStatus.Active();
    }

    public boolean isActive() {
        return this.status instanceof TenantStatus.Active;
    }

    // 도메인 규칙: 정지된 테넌트는 라우팅 불가
    public void validateRoutable() {
        switch (status) {
            case TenantStatus.Active a   -> { /* 통과 */ }
            case TenantStatus.Suspended s ->
                    throw new RuntimeException("정지된 테넌트입니다. tenantId=%s, reason=%s"
                            .formatted(id, s.reason()));
        }
    }
}
