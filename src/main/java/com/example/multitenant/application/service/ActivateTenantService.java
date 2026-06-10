package com.example.multitenant.application.service;

import com.example.multitenant.application.port.in.ActivateTenantUseCase;
import com.example.multitenant.application.port.in.command.ActivateTenantCommand;
import com.example.multitenant.application.port.in.results.ActivateTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.domain.tenant.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 재활성화 비즈니스 구현.
 *
 * <h2>처리 흐름</h2>
 * <ol>
 *   <li>테넌트 조회 — 존재하지 않으면 {@link TenantNotFoundException}</li>
 *   <li>{@link Tenant#activate} — 이미 활성 상태면 {@code TenantAlreadyActiveException}</li>
 *   <li>마스터 DB 상태 업데이트 (@Transactional)</li>
 *   <li>DataSource 재등록 + 라우팅 테이블 추가</li>
 * </ol>
 *
 * <h2>DataSource 재등록</h2>
 * 정지 시 HikariCP 풀이 닫혔으므로, 재활성화 시 DataSourceSpec 으로 새 풀을 생성한다.
 * 스키마 초기화는 {@code IF NOT EXISTS} 로 멱등하므로 재실행해도 안전하다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@Service
public class ActivateTenantService implements ActivateTenantUseCase {

    private final TenantPersistencePort persistencePort;
    private final TenantDataSourcePort  dataSourcePort;

    public ActivateTenantService(TenantPersistencePort persistencePort,
                                 TenantDataSourcePort  dataSourcePort) {
        this.persistencePort = persistencePort;
        this.dataSourcePort  = dataSourcePort;
    }

    @Override
    @Transactional
    public ActivateTenantResult activate(ActivateTenantCommand command) {
        TenantId id = new TenantId(command.tenantId());

        Tenant tenant = persistencePort.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));

        tenant.activate();                                                                        // 도메인 규칙 검증 + 상태 전환
        persistencePort.save(tenant);                                                             // 마스터 DB 커밋 (트랜잭션 내)
        dataSourcePort.register(id, tenant.getDataSourceSpec(), tenant.getSlaveSpecs()); // 풀 재생성 + 라우팅 추가

        return ActivateTenantResult.from(tenant);
    }
}
