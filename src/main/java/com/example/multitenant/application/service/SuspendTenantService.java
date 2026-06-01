package com.example.multitenant.application.service;

import com.example.multitenant.application.port.in.SuspendTenantUseCase;
import com.example.multitenant.application.port.in.command.SuspendTenantCommand;
import com.example.multitenant.application.port.in.results.SuspendTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.domain.tenant.Tenant;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.domain.tenant.TenantNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 정지 비즈니스 구현.
 *
 * <h2>처리 흐름</h2>
 * <ol>
 *   <li>테넌트 조회 — 존재하지 않으면 {@link TenantNotFoundException}</li>
 *   <li>{@link Tenant#suspend} — 이미 정지 상태면 {@code TenantAlreadySuspendedException}</li>
 *   <li>마스터 DB 상태 업데이트 (@Transactional)</li>
 *   <li>라우팅 테이블 제거 + HikariCP 풀 종료</li>
 * </ol>
 *
 * <h2>트랜잭션 경계</h2>
 * DB 업데이트({@code persistencePort.save})까지만 트랜잭션을 유지한다.
 * {@code dataSourcePort.deregister}는 HikariCP 풀을 종료하는 부수 효과가 있어
 * 트랜잭션 롤백 대상이 아니므로 커밋 이후에 실행된다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@Service
public class SuspendTenantService implements SuspendTenantUseCase {

    private final TenantPersistencePort persistencePort;
    private final TenantDataSourcePort  dataSourcePort;

    public SuspendTenantService(TenantPersistencePort persistencePort,
                                TenantDataSourcePort  dataSourcePort) {
        this.persistencePort = persistencePort;
        this.dataSourcePort  = dataSourcePort;
    }

    @Override
    @Transactional
    public SuspendTenantResult suspend(SuspendTenantCommand command) {
        TenantId id = new TenantId(command.tenantId());

        Tenant tenant = persistencePort.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));

        tenant.suspend(command.reason());       // 도메인 규칙 검증 + 상태 전환
        persistencePort.save(tenant);           // 마스터 DB 커밋 (트랜잭션 내)
        dataSourcePort.deregister(id);          // 라우팅 제거 + 풀 종료

        return SuspendTenantResult.from(tenant);
    }
}
