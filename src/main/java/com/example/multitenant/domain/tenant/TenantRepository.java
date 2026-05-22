package com.example.multitenant.domain.tenant;

import java.util.List;
import java.util.Optional;

/**
 * Tenant Repository Port
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantRepository {
    // 저장
    void save(Tenant tenant);
    // 단일 조회
    Optional<Tenant> findById(TenantId id);
    // 운영중인 테넌트 목록 조회
    List<Tenant> findAllActive();
    // 테넌트 존재여부 조회
    boolean existsById(TenantId id);
}