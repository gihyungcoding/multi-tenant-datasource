package com.example.multitenant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 테넌트 replica DataSource 레포지토리.
 *
 * @author gihyung.lee
 * @since 2026-06-10
 */
public interface TenantReplicaJpaRepository extends JpaRepository<TenantReplicaJpaEntity, Long> {

    /** ordinal 오름차순으로 조회 — round-robin 라우팅 순서와 일치시킨다. */
    List<TenantReplicaJpaEntity> findAllByTenantIdOrderByOrdinal(String tenantId);

    /** 테넌트의 기존 replica 전체 삭제 — save() 전 호출하여 교체한다. */
    @Modifying
    @Query("DELETE FROM TenantReplicaJpaEntity r WHERE r.tenantId = :tenantId")
    void deleteAllByTenantId(@Param("tenantId") String tenantId);
}
