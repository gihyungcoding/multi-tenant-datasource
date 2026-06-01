package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry.UnregisterResult;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceAdapter implements TenantDataSourcePort {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceAdapter.class);

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
     */
    @Override
    public synchronized void register(TenantId tenantId, DataSourceSpec spec) {
        Map<String, DataSource> snapshot = registry.registerAndSnapshot(tenantId, spec);
        // 테넌트 DB 스키마 초기화 — demo_message 테이블 및 시퀀스를 생성한다.
        schemaInitializer.initialize(registry.get(tenantId));
        routingDataSource.refresh(snapshot);
    }

    /**
     * 테넌트 DataSource 를 라우팅 테이블에서 제거하고 HikariCP 풀을 종료한다.
     *
     * <h2>처리 순서가 중요한 이유</h2>
     * <ol>
     *   <li>{@code unregisterAndSnapshot} — 레지스트리에서 원자적으로 제거 + 스냅샷 포착</li>
     *   <li>{@code routingDataSource.refresh} — 라우팅 테이블 갱신 (신규 요청 차단)</li>
     *   <li>{@code closeQuietly} — HikariCP 풀 종료 (기존 커넥션 반납 대기)</li>
     * </ol>
     *
     * <p>2단계가 3단계보다 먼저 실행되어야 한다.
     * 순서가 바뀌면 풀이 닫힌 뒤 라우팅 테이블 갱신 전 찰나에 신규 요청이
     * 닫힌 풀로 연결을 시도하여 불필요한 오류가 발생한다.
     *
     * <p>락을 보유한 채 풀을 종료하는 것은 시간이 걸릴 수 있지만,
     * 등록/해제는 관리자 API 를 통해 드물게 발생하므로 실제 운영 영향은 미미하다.
     */
    @Override
    public synchronized void deregister(TenantId tenantId) {
        UnregisterResult result = registry.unregisterAndSnapshot(tenantId);
        routingDataSource.refresh(result.snapshot());    // ① 라우팅 먼저 차단
        closeQuietly(tenantId, result.removed());        // ② 풀 종료
    }

    @Override
    public boolean isRegistered(TenantId tenantId) {
        return registry.isRegistered(tenantId);
    }

    @Override
    public void refreshRoutingTable() {
        routingDataSource.refresh(registry.snapshot());
    }

    private void closeQuietly(TenantId tenantId, DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari && !hikari.isClosed()) {
            hikari.close();
            log.info("HikariCP 풀 종료: tenantId={}, pool={}", tenantId.value(), hikari.getPoolName());
        }
    }
}
