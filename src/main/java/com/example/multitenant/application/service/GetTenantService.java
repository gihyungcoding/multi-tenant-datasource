package com.example.multitenant.application.service;

import com.example.multitenant.application.port.in.command.GetTenantCommand;
import com.example.multitenant.application.port.in.GetTenantUseCase;
import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.domain.tenant.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Service
@Transactional(readOnly = true)
public class GetTenantService implements GetTenantUseCase {

    private final TenantPersistencePort persistencePort;

    public GetTenantService(TenantPersistencePort persistencePort) {
        this.persistencePort = persistencePort;
    }

    @Override
    public GetTenantResult getById(GetTenantCommand command) {
        TenantId tenantId = new TenantId(command.tenantId());
        Tenant tenant = persistencePort.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        return GetTenantResult.from(tenant);
    }
}
