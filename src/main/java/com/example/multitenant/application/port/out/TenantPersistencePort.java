package com.example.multitenant.application.port.out;

import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantPersistencePort {
    void save(Tenant tenant);
    Optional<Tenant> findById(TenantId id);
    List<Tenant> findAllActive();
    boolean existsById(TenantId id);
}
