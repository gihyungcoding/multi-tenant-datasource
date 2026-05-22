package com.example.multitenant.application.service;

import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Service
public class ResolveTenantDataSourceService implements ResolveTenantDataSourceUseCase {

    private final TenantPersistencePort persistencePort;
    private final TenantContextHolder contextHolder;

    public ResolveTenantDataSourceService(TenantPersistencePort persistencePort,
                                          TenantContextHolder contextHolder) {
        this.persistencePort = persistencePort;
        this.contextHolder = contextHolder;
    }

    @Override
    @Transactional(readOnly = true)
    public void resolve(ResolveTenantCommand command) {
        TenantId tenantId = new TenantId(command.tenantId());

        Tenant tenant = persistencePort.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 테넌트입니다: " + tenantId));

        // 도메인 규칙 검증 — 정지된 테넌트 차단
        tenant.validateRoutable();

        contextHolder.setTenant(tenantId);
    }
}
