package com.example.multitenant.domain.tenant;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 테넌트 애그리거트 루트
 * @author gihyung.lee
 * @since 2026-05-21
 */
public class Tenant {

    private final TenantId id;
    private final DataSourceSpec dataSourceSpec;
    private final List<DataSourceSpec> slaveSpecs; // 비어있으면 master 단독 구성
    private TenantStatus status;
    private LocalDateTime createdAt;

    private Tenant(TenantId id, DataSourceSpec dataSourceSpec, List<DataSourceSpec> slaveSpecs,
                   TenantStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.dataSourceSpec = dataSourceSpec;
        this.slaveSpecs = List.copyOf(slaveSpecs);
        this.status = status;
        this.createdAt = createdAt;
    }

    public TenantId getId() {
        return id;
    }

    public DataSourceSpec getDataSourceSpec() {
        return dataSourceSpec;
    }

    /**
     * slave DataSource 접속 정보 목록.
     * 비어있으면 slave 미구성 → master 단독 운영.
     */
    public List<DataSourceSpec> getSlaveSpecs() {
        return slaveSpecs;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // 생성 팩토리 메서드 — 최초 등록 시 사용. createdAt 을 현재 시각으로 설정한다.
    public static Tenant create(TenantId id, DataSourceSpec masterSpec, List<DataSourceSpec> slaveSpecs) {
        return new Tenant(id, masterSpec, slaveSpecs, new TenantStatus.Active(), LocalDateTime.now());
    }

    /**
     * 영속 계층 복원 팩토리 메서드 — DB 레코드로부터 재구성 시 사용.
     *
     * <p>{@link #create} 와 달리 모든 필드를 인자로 받으므로
     * {@code createdAt} 이 로드 시점의 현재 시각으로 덮어씌워지는 버그를 방지한다.
     */
    public static Tenant restore(TenantId id, DataSourceSpec masterSpec, List<DataSourceSpec> slaveSpecs,
                                 TenantStatus status, LocalDateTime createdAt) {
        return new Tenant(id, masterSpec, slaveSpecs, status, createdAt);
    }

    /**
     * 테넌트를 정지한다.
     *
     * @throws TenantAlreadySuspendedException 이미 정지된 상태인 경우 (409 Conflict)
     */
    public void suspend(String reason) {
        if (this.status instanceof TenantStatus.Suspended) {
            throw new TenantAlreadySuspendedException(id);
        }
        this.status = new TenantStatus.Suspended(reason);
    }

    /**
     * 테넌트를 재활성화한다.
     *
     * @throws TenantAlreadyActiveException 이미 활성 상태인 경우 (409 Conflict)
     */
    public void activate() {
        if (this.status instanceof TenantStatus.Active) {
            throw new TenantAlreadyActiveException(id);
        }
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
