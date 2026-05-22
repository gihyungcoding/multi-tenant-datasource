package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 멀티 테넌트 최초 주입
 * 톰캣이 실행 되기 전 초기화가 완료되어야함
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceInitializer implements SmartInitializingSingleton  {
    private static final Logger log =
            LoggerFactory.getLogger(TenantDataSourceInitializer.class);

    private final TenantPersistencePort persistencePort;
    private final TenantDataSourcePort dataSourcePort;

    public TenantDataSourceInitializer(TenantPersistencePort persistencePort,
                                       TenantDataSourcePort dataSourcePort) {
        this.persistencePort = persistencePort;
        this.dataSourcePort = dataSourcePort;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("테넌트 DataSource 초기화 시작");

        persistencePort.findAllActive().forEach(tenant -> {
            dataSourcePort.register(
                    tenant.getId(),
                    tenant.getDataSourceSpec()
            );
            log.info("테넌트 등록: {}", tenant.getId());
        });

        log.info("테넌트 DataSource 초기화 완료");
    }
}
