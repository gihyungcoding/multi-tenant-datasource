package com.example.multitenant.infrastructure.persistence;

import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.domain.tenant.TenantStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantPersistenceAdapter implements TenantPersistencePort {

    private final TenantJpaRepository jpaRepository;

    public TenantPersistenceAdapter(TenantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(Tenant tenant) {
        jpaRepository.save(toEntity(tenant));
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return jpaRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Tenant> findAllActive() {
        return jpaRepository.findAllByStatus(TenantStatus.ACTIVE_CODE)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsById(TenantId id) {
        return jpaRepository.existsById(id.value());
    }

    // Entity <-> Domain Mapping
    private TenantJpaEntity toEntity(Tenant tenant) {
        TenantStatus status = tenant.getStatus();
        String reason = status instanceof TenantStatus.Suspended s
                ? s.reason() : null;

        return new TenantJpaEntity(
                tenant.getId().value(),
                tenant.getDataSourceSpec().url(),
                tenant.getDataSourceSpec().username(),
                tenant.getDataSourceSpec().password(),
                status.code(),
                reason
        );
    }
    // Entity <-> Domain Mapping
    private Tenant toDomain(TenantJpaEntity entity) {
        TenantId id = new TenantId(entity.getTenantId());
        DataSourceSpec spec = new DataSourceSpec(
                entity.getUrl(),
                entity.getUsername(),
                entity.getPassword()
        );

        TenantStatus status = TenantStatus.from(
                entity.getStatus(),
                entity.getSuspendReason()
        );

        Tenant tenant = Tenant.create(id, spec);
        if (status instanceof TenantStatus.Suspended s) {
            tenant.suspend(s.reason());
        }
        return tenant;
    }
}
