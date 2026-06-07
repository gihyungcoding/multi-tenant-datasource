package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceAdapter;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import com.example.multitenant.infrastructure.datasource.TenantSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link TenantDataSourceAdapter#register} 의 비원자성 race condition 재현 → 수정 검증 테스트.
 *
 * <h2>버그 설명 (수정 전)</h2>
 * <pre>
 *   public void register(TenantId tenantId, DataSourceSpec spec) {
 *       registry.register(tenantId, spec);               // [1]
 *       routingDataSource.refresh(registry.snapshot());  // [2] — [1]과 원자적이지 않음
 *   }
 * </pre>
 *
 * [1]과 [2] 사이에 다른 스레드가 끼어드는 stale snapshot 경쟁:
 * <pre>
 *   Thread A: register("a"), snapshot = {a}  ← stale 포착
 *   Thread B: register("b"), snapshot = {a,b}
 *   Thread B: refresh({a,b})                 ← 라우팅 정상 ✓
 *   Thread A: refresh({a})                   ← stale → b 소멸 ✗
 * </pre>
 *
 * <h2>수정 방향</h2>
 * <ol>
 *   <li>{@link TenantDataSourceRegistry#registerAndSnapshot} 도입: 등록과 스냅샷 포착을
 *       {@code synchronized} 블록에서 원자적으로 처리한다.</li>
 *   <li>{@link TenantDataSourceAdapter#register} 에 {@code synchronized} 추가: refresh() 호출까지
 *       같은 임계 영역에 포함시켜 stale snapshot 의 라우팅 테이블 오염을 원천 차단한다.</li>
 * </ol>
 *
 * <h2>테스트 전략</h2>
 * <ul>
 *   <li>{@link FakeTenantDataSourceRegistry} — HikariCP 없이 동작하는 레지스트리.
 *       Small 테스트 제약(I/O 금지) 준수.</li>
 *   <li>{@link RaceConditionInducingRegistry} — {@code registerAndSnapshot()} 에 1ms sleep 주입.
 *       <ul>
 *         <li>구버전(비동기화 adapter): 50개 스레드가 sleep 중 동시 경쟁 →
 *             stale snapshot 으로 refresh 충돌 → 테넌트 누락 가능</li>
 *         <li>신버전(synchronized adapter): sleep 중에도 락을 보유 →
 *             직렬 실행 → 각 refresh 는 누적 스냅샷 적용 → 항상 정확</li>
 *       </ul>
 *   </li>
 *   <li>{@link InspectableRoutingDataSource} — {@code protected getResolvedDataSources()} 를
 *       {@code routes()} 로 공개하여 라우팅 테이블 상태를 검증한다.</li>
 * </ul>
 */
@SmallTest
@DisplayName("TenantDataSourceAdapter - register() 동시성 버그 재현 및 수정 검증")
class TenantDataSourceAdapterConcurrencyTest {

    // ── 테스트 전용 보조 클래스 ─────────────────────────────────────────────

    /**
     * AbstractRoutingDataSource.getResolvedDataSources() (protected) 를
     * routes() 로 공개하여 라우팅 테이블 상태를 테스트에서 직접 검증할 수 있게 한다.
     */
    static class InspectableRoutingDataSource extends RoutingDataSource {
        InspectableRoutingDataSource(TenantContextHolder ctx) {
            super(ctx);
        }

        /** 현재 라우팅 테이블 (테넌트 키 → DataSource) */
        Map<Object, DataSource> routes() {
            return getResolvedDataSources();
        }
    }

    /**
     * HikariCP 없이 동작하는 기본 가짜 레지스트리.
     *
     * <p>부모 클래스의 {@link TenantDataSourceRegistry#registerAndSnapshot} 은 HikariDataSource 를
     * 생성하므로 Small 테스트에서 사용할 수 없다. registerAndSnapshot/snapshot/isRegistered 를
     * 오버라이드하여 mock DataSource 만 보관한다.
     */
    static class FakeTenantDataSourceRegistry extends TenantDataSourceRegistry {
        protected final Map<String, DataSource> fakeMap = new ConcurrentHashMap<>();

        @Override
        public Map<String, DataSource> registerAndSnapshot(TenantId tenantId, DataSourceSpec spec) {
            fakeMap.put(tenantId.value(), mock(DataSource.class));
            return Map.copyOf(fakeMap);
        }

        @Override
        public DataSource get(TenantId tenantId) {
            return fakeMap.get(tenantId.value()); // fakeMap 사용 (부모의 dataSourceMap 우회)
        }

        @Override
        public boolean isRegistered(TenantId tenantId) {
            return fakeMap.containsKey(tenantId.value());
        }

        @Override
        public Map<String, DataSource> snapshot() {
            return Map.copyOf(fakeMap);
        }
    }

    /**
     * {@code registerAndSnapshot()} 에 1ms sleep 을 주입하는 레지스트리 — 동시성 스트레스 전용.
     *
     * <h3>왜 sleep 을 주입하는가</h3>
     * <pre>
     *   구버전 adapter(비동기화):
     *     50개 스레드가 sleep 중에 서로 경쟁하여 각자의 (가능하면 stale) snapshot 으로
     *     refresh() 를 동시에 호출 → 마지막 refresh 가 stale snapshot 으로 덮어쓸 가능성 ↑
     *
     *   신버전 adapter(synchronized):
     *     Thread 0 이 락을 보유한 채로 sleep → Thread 1~49 는 대기
     *     Thread 0 의 refresh 완료 후 Thread 1 이 락 획득 → 순차 실행
     *     각 스레드의 snapshot 은 직전까지 등록된 모든 테넌트를 포함 → 항상 정확
     * </pre>
     */
    static class RaceConditionInducingRegistry extends FakeTenantDataSourceRegistry {
        private static final long SLEEP_MS = 1L;

        @Override
        public Map<String, DataSource> registerAndSnapshot(TenantId tenantId, DataSourceSpec spec) {
            fakeMap.put(tenantId.value(), mock(DataSource.class));
            Map<String, DataSource> snap = Map.copyOf(fakeMap); // 스냅샷 즉시 포착
            try {
                Thread.sleep(SLEEP_MS); // 다른 스레드의 register 를 허용하는 시간 창 (구버전에서 race 유발)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return snap;
        }
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    /** RoutingDataSource 생성에만 필요한 컨텍스트 홀더 — refresh() 경로에서는 사용되지 않는다 */
    private final TenantContextHolder dummyCtx = new TenantContextHolder() {
        @Override public void setTenant(TenantId id) {}
        @Override public TenantId getTenant()        { return null; }
        @Override public boolean hasTenant()         { return false; }
        @Override public void clear()                {}
    };

    /** 실제 DB 접속 정보는 필요 없다 — FakeTenantDataSourceRegistry 가 HikariCP 를 건너뜀 */
    private static final DataSourceSpec DUMMY_SPEC =
            new DataSourceSpec("jdbc:fake://host/db", "user", "pass");

    // 스키마 초기화는 이 테스트의 관심사가 아니므로 no-op mock 으로 대체
    private final TenantSchemaInitializer      noOpSchema    = mock(TenantSchemaInitializer.class);
    private final FakeTenantDataSourceRegistry fakeRegistry  = new FakeTenantDataSourceRegistry();
    private final InspectableRoutingDataSource routing       = new InspectableRoutingDataSource(dummyCtx);
    private final TenantDataSourceAdapter      adapter       = new TenantDataSourceAdapter(fakeRegistry, routing, noOpSchema, mock(TenantRequestTracker.class));

    // ── 테스트 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("버그 패턴 문서화 (라우팅 레이어)")
    class BugDocumentation {

        /**
         * {@code routing.refresh(staleSnapshot)} 은 라우팅 테이블을 덮어쓴다.
         *
         * <p>이 테스트는 adapter 를 우회하여 routing 레이어 직접 호출로 stale refresh 의
         * 파괴적 효과를 시연한다. adapter.register() 에 synchronized 를 적용해야 하는 이유다.
         *
         * <p>항상 통과 (bug 의 존재를 확인하는 characterization test).
         */
        @Test
        @DisplayName("[버그 패턴] routing.refresh(staleSnapshot) 은 라우팅 테이블을 오염시킨다")
        void routingRefresh_withStaleSnapshot_corruptsRoutingTable() {
            // Thread A: tenant-a 등록 직후 stale snapshot 포착
            fakeRegistry.registerAndSnapshot(new TenantId("tenant-a"), DUMMY_SPEC);
            Map<String, DataSource> staleSnapshot = Map.copyOf(fakeRegistry.snapshot()); // {tenant-a}

            // Thread B: tenant-b 등록 → 올바른 스냅샷으로 refresh 완료
            fakeRegistry.registerAndSnapshot(new TenantId("tenant-b"), DUMMY_SPEC);
            routing.refresh(fakeRegistry.snapshot()); // {tenant-a, tenant-b}
            assertThat(routing.routes()).as("Thread B refresh 직후").containsKeys("tenant-a", "tenant-b");

            // Thread A: 뒤늦게 stale snapshot 으로 refresh → tenant-b 소멸
            routing.refresh(staleSnapshot); // {tenant-a} only — STALE OVERWRITE

            // 이것이 버그다: adapter.register() 의 synchronized 가 이 시나리오를 방지한다
            assertThat(routing.routes())
                    .as("stale refresh 는 라우팅 테이블을 오염시킨다 — adapter.synchronized 가 이를 방지해야 함")
                    .doesNotContainKey("tenant-b"); // stale refresh 로 tenant-b 가 실제로 사라짐을 확인
        }
    }

    @Nested
    @DisplayName("수정 검증 (adapter 레이어)")
    class FixVerification {

        /**
         * adapter.register() 를 두 번 호출하면 두 테넌트 모두 라우팅 테이블에 존재한다.
         *
         * <p>순차 호출이므로 synchronized 없이도 동작하지만, 이 케이스도 올바르게
         * 처리되어야 하는 기본 회귀 테스트다.
         */
        @Test
        @DisplayName("[수정 검증] 두 테넌트를 순서대로 등록하면 라우팅 테이블에 모두 존재한다")
        void register_twoTenantsSequentially_bothInRoutingTable() {
            adapter.register(new TenantId("tenant-a"), DUMMY_SPEC);
            adapter.register(new TenantId("tenant-b"), DUMMY_SPEC);

            assertThat(routing.routes())
                    .as("두 테넌트 모두 라우팅 테이블에 존재해야 한다")
                    .containsKeys("tenant-a", "tenant-b");
        }

        /**
         * 50개 Virtual Thread 가 동시에 {@code adapter.register()} 를 호출할 때
         * 모든 테넌트가 최종 라우팅 테이블에 존재해야 한다.
         *
         * <h3>수정 전 (비동기화 adapter + RaceConditionInducingRegistry)</h3>
         * 50개 스레드가 sleep(1ms) 중 동시 실행 → 각자의 stale snapshot 으로 refresh() 경쟁 →
         * 마지막 refresh 가 stale snapshot 을 적용하면 다수 테넌트 누락.
         *
         * <h3>수정 후 (synchronized adapter + RaceConditionInducingRegistry)</h3>
         * sleep(1ms) 중에도 adapter 락을 보유 → 스레드 직렬 실행 →
         * 각 refresh 는 직전까지 등록된 모든 테넌트를 포함한 누적 스냅샷 적용 → 항상 정확.
         */
        @Test
        @DisplayName("[수정 검증] 50개 Virtual Thread 동시 등록 후 모든 테넌트가 라우팅 테이블에 존재한다")
        void concurrent50VirtualThreads_allTenantsRouted() throws InterruptedException {
            int count = 50;

            RaceConditionInducingRegistry racyRegistry =
                    new RaceConditionInducingRegistry();
            InspectableRoutingDataSource racyRouting =
                    new InspectableRoutingDataSource(dummyCtx);
            TenantDataSourceAdapter racyAdapter =
                    new TenantDataSourceAdapter(racyRegistry, racyRouting, mock(TenantSchemaInitializer.class), mock(TenantRequestTracker.class));

            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(count);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < count; i++) {
                    String tenantId = "tenant-" + i;
                    executor.submit(() -> {
                        ready.countDown();
                        try {
                            start.await(); // 모든 스레드가 준비될 때까지 대기
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        racyAdapter.register(new TenantId(tenantId), DUMMY_SPEC);
                        done.countDown();
                    });
                }

                ready.await();
                start.countDown(); // 모든 스레드를 동시에 출발
                done.await(30, TimeUnit.SECONDS);
            }

            Set<Object> routedTenants = racyRouting.routes().keySet();

            // 수정 전 → sleep 중 stale snapshot 경쟁으로 일부 누락 가능 (실패)
            // 수정 후 → synchronized 로 직렬화되어 항상 count 개 보장 (통과)
            assertThat(routedTenants)
                    .as("등록된 테넌트: %d개, 라우팅 테이블 실제 크기: %d개",
                            count, routedTenants.size())
                    .hasSize(count);
        }
    }
}
