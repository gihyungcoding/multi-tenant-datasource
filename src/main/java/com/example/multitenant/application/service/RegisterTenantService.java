package com.example.multitenant.application.service;

import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.tenant.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 등록 비즈니스 구현
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Service
public class RegisterTenantService implements RegisterTenantUseCase {

    private final TenantPersistencePort persistencePort;
    private final TenantDataSourcePort dataSourcePort;

    public RegisterTenantService(TenantPersistencePort persistencePort, TenantDataSourcePort dataSourcePort) {
        this.persistencePort = persistencePort;
        this.dataSourcePort = dataSourcePort;
    }

    @Override
    @Transactional
    public RegisterTenantResult register(RegisterTenantCommand command) {
        TenantId id = new TenantId(command.tenantId());
        DataSourceSpec spec = new DataSourceSpec(
                command.url(), command.username(), command.password()
        );

        if (persistencePort.existsById(id)) {
            throw new TenantAlreadyExistsException(id);
        }

        Tenant tenant = Tenant.create(id, spec);
        persistencePort.save(tenant);
        dataSourcePort.register(id, spec);

        return RegisterTenantResult.from(tenant);
    }
}
