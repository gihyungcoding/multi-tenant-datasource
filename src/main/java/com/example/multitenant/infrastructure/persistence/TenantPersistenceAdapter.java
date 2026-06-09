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

    // ── Entity ↔ Domain 변환 ─────────────────────────────────────────────────

    private TenantJpaEntity toEntity(Tenant tenant) {
        TenantStatus status = tenant.getStatus();
        String reason = status instanceof TenantStatus.Suspended s ? s.reason() : null;
        DataSourceSpec slave = tenant.getSlaveDataSourceSpec();

        return new TenantJpaEntity(
                tenant.getId().value(),
                tenant.getDataSourceSpec().url(),
                tenant.getDataSourceSpec().username(),
                tenant.getDataSourceSpec().password(),
                slave != null ? slave.url()      : null,
                slave != null ? slave.username() : null,
                slave != null ? slave.password() : null,
                status.code(),
                reason,
                tenant.getCreatedAt()
        );
    }

    /**
     * DB 레코드로부터 테넌트 도메인 객체를 복원한다.
     *
     * <p>{@link Tenant#create}가 아닌 {@link Tenant#restore}를 사용하여
     * 로드 시점의 현재 시각이 {@code createdAt}을 덮어쓰는 버그를 방지한다.
     */
    private Tenant toDomain(TenantJpaEntity entity) {
        TenantId       id          = new TenantId(entity.getTenantId());
        DataSourceSpec masterSpec  = new DataSourceSpec(entity.getUrl(), entity.getUsername(), entity.getPassword());
        DataSourceSpec slaveSpec   = entity.getSlaveUrl() != null
                ? new DataSourceSpec(entity.getSlaveUrl(), entity.getSlaveUsername(), entity.getSlavePassword())
                : null;
        TenantStatus   status      = TenantStatus.from(entity.getStatus(), entity.getSuspendReason());

        return Tenant.restore(id, masterSpec, slaveSpec, status, entity.getCreatedAt());
    }
}
