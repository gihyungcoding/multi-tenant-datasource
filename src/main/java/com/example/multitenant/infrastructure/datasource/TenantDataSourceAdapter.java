package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceAdapter implements TenantDataSourcePort {

    private final TenantDataSourceRegistry  registry;
    private final RoutingDataSource          routingDataSource;
    private final TenantSchemaInitializer    schemaInitializer;

    public TenantDataSourceAdapter(TenantDataSourceRegistry registry,
                                   RoutingDataSource routingDataSource,
                                   TenantSchemaInitializer schemaInitializer) {
        this.registry          = registry;
        this.routingDataSource = routingDataSource;
        this.schemaInitializer = schemaInitializer;
    }

    /**
     * 테넌트 DataSource 를 등록하고 라우팅 테이블을 갱신한다.
     *
     * <p><b>왜 synchronized 인가</b><br>
     * {@link TenantDataSourceRegistry#registerAndSnapshot} 은 등록과 스냅샷 포착을 원자적으로
     * 수행한다. 그러나 {@link RoutingDataSource#refresh} 는 여전히 별도 호출이므로,
     * synchronized 없이는 다음 race 가 발생한다.
     *
     * <pre>
     *   Thread A: registerAndSnapshot("a") = {a}   ← snapshot 포착
     *   Thread B: registerAndSnapshot("b") = {a,b}
     *   Thread B: refresh({a,b}) → 라우팅 정상 ✓
     *   Thread A: refresh({a})   → b 소멸 ✗  (stale snapshot 이 나중에 적용됨)
     * </pre>
     *
     * <p>{@code synchronized} 를 추가하면 registerAndSnapshot + refresh 가 직렬화된다.
     * 나중에 락을 획득하는 스레드일수록 더 누적된(최신) 스냅샷을 얻으며,
     * 그 스냅샷으로 refresh 를 마지막에 실행하므로 라우팅 테이블은 항상 완전하다.
     *
     * <p><b>성능 트레이드오프</b><br>
     * HikariDataSource 생성이 {@code synchronized} 블록 안에서 발생하므로
     * 동시 테넌트 등록 시 직렬화 오버헤드가 존재한다. 테넌트 등록은 관리자 API 를 통해
     * 드물게 발생하는 연산이므로 실제 운영 영향은 미미하다.
     */
    @Override
    public synchronized void register(TenantId tenantId, DataSourceSpec spec) {
        Map<String, DataSource> snapshot = registry.registerAndSnapshot(tenantId, spec);
        // 테넌트 DB 스키마 초기화 — demo_message 테이블 및 시퀀스를 생성한다.
        // IF NOT EXISTS 구문으로 멱등하게 동작하므로 재기동 시에도 안전하다.
        schemaInitializer.initialize(registry.get(tenantId));
        routingDataSource.refresh(snapshot);
    }

    @Override
    public boolean isRegistered(TenantId tenantId) {
        return registry.isRegistered(tenantId);
    }

    @Override
    public void refreshRoutingTable() {
        routingDataSource.refresh(registry.snapshot());
    }

}
