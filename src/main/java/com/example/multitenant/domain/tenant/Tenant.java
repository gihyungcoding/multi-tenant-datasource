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

    // 생성 팩토리 메서드 — 최초 등록 시 사용. createdAt 을 현재 시각으로 설정한다.
    public static Tenant create(TenantId id, DataSourceSpec spec) {
        return new Tenant(id, spec, new TenantStatus.Active(), LocalDateTime.now());
    }

    /**
     * 영속 계층 복원 팩토리 메서드 — DB 레코드로부터 재구성 시 사용.
     *
     * <p>{@link #create} 와 달리 모든 필드를 인자로 받으므로
     * {@code createdAt} 이 로드 시점의 현재 시각으로 덮어씌워지는 버그를 방지한다.
     */
    public static Tenant restore(TenantId id, DataSourceSpec spec,
                                 TenantStatus status, LocalDateTime createdAt) {
        return new Tenant(id, spec, status, createdAt);
    }

    // 정지 상태로 변경
    public void suspend(String reason) {
        if (this.status instanceof TenantStatus.Suspended) {
            throw new TenantSuspendedException(id, reason);
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
                    throw new TenantSuspendedException(id, s.reason());
        }
    }
}
