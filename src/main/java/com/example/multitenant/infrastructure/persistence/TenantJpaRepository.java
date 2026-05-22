package com.example.multitenant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Tenant JPA Repository
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, String> {
    List<TenantJpaEntity> findAllByStatus(String status);
}
