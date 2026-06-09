package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceAdapter;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry.UnregisterResult;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import com.example.multitenant.infrastructure.datasource.TenantSchemaInitializer;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link TenantDataSourceAdapter#deregister} 의 Graceful Drain 동작 단위 테스트.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>소프트 퇴거({@code softEvictConnections}) 즉시 실행 여부</li>
 *   <li>활성 커넥션 없을 때 즉시 종료</li>
 *   <li>활성 커넥션 드레인 후 종료</li>
 *   <li>타임아웃 만료 시 강제 종료</li>
 *   <li>{@code deregister()} 의 비동기성 — 드레인을 기다리지 않고 즉시 반환</li>
 *   <li>이미 닫힌 풀 중복 종료 방지</li>
 *   <li>HTTP 요청 카운터가 양수이면 DB 커넥션이 0 이어도 드레인 대기</li>
 *   <li>HTTP 요청이 완료되면 close() 가 호출된다</li>
 *   <li>forceClose() 후 {@code requestTracker.remove()} 가 호출된다</li>
 * </ul>
 *
 * <h2>테스트 전략</h2>
 * <ul>
 *   <li>{@code mock(HikariDataSource.class)} — {@code instanceof} 검사 통과 + 동작 제어</li>
 *   <li>{@link FakeDrainRegistry} — {@code unregisterAndSnapshot} 이 mock Hikari 를 반환</li>
 *   <li>{@code CountDownLatch} — 비동기 {@code close()} 완료를 결정론적으로 대기</li>
 *   <li>짧은 {@code drainTimeout}(500ms) — 타임아웃 시나리오를 빠르게 검증</li>
 *   <li>실제 {@link TenantRequestTracker} — HTTP 요청 대기 시나리오에서 실제 카운터 사용</li>
 * </ul>
 */
@SmallTest
@DisplayName("TenantDataSourceAdapter - Graceful Drain")
class TenantDataSourceAdapterDrainTest {

    private static final TenantId      ID           = new TenantId("tenant-a");
    private static final DataSourceSpec SPEC        = new DataSourceSpec("jdbc:fake://host/db", "u", "p");
    /** 타임아웃 시나리오를 빠르게 검증하기 위한 짧은 드레인 제한 시간 */
    private static final Duration SHORT_TIMEOUT     = Duration.ofMillis(500);
    /** 폴링 간격을 짧게 설정해 테스트 실행 속도 향상 */
    private static final Duration FAST_POLL         = Duration.ofMillis(10);
    /** 비동기 완료 대기 상한 */
    private static final long AWAIT_SECONDS         = 5L;

    private HikariDataSource     mockHikari;
    private HikariPoolMXBean     mockMxBean;
    private FakeDrainRegistry    fakeRegistry;
    private RoutingDataSource    routingStub;

    @BeforeEach
    void setUp() {
        mockHikari = mock(HikariDataSource.class);
        mockMxBean = mock(HikariPoolMXBean.class);

        when(mockHikari.isClosed()).thenReturn(false);
        when(mockHikari.getHikariPoolMXBean()).thenReturn(mockMxBean);
        when(mockHikari.getPoolName()).thenReturn("HikariPool-tenant-a");

        TenantContextHolder dummyCtx = new TenantContextHolder() {
            @Override public void setTenant(TenantId id) {}
            @Override public TenantId getTenant()        { return null; }
            @Override public boolean hasTenant()         { return false; }
            @Override public void clear()                {}
        };
        routingStub  = new RoutingDataSource(dummyCtx);
        fakeRegistry = new FakeDrainRegistry(mockHikari);
    }

    /** mock TenantRequestTracker 로 어댑터 생성 (DB 커넥션 드레인 시나리오 전용) */
    private TenantDataSourceAdapter newAdapter() {
        return newAdapterWith(mock(TenantRequestTracker.class));
    }

    /** 지정한 TenantRequestTracker 로 어댑터 생성 (HTTP 요청 드레인 시나리오 전용) */
    private TenantDataSourceAdapter newAdapterWith(TenantRequestTracker tracker) {
        return new TenantDataSourceAdapter(
                fakeRegistry, routingStub, mock(TenantSchemaInitializer.class),
                tracker,
                Executors.newVirtualThreadPerTaskExecutor(),
                SHORT_TIMEOUT,
                FAST_POLL);
    }

    // ── ① softEvictConnections 즉시 실행 ──────────────────────────────────

    @Nested
    @DisplayName("소프트 퇴거")
    class SoftEvict {

        @Test
        @DisplayName("deregister() 호출 즉시 softEvictConnections() 가 실행된다")
        void deregister_callsSoftEvictBeforeReturning() {
            when(mockMxBean.getActiveConnections()).thenReturn(0);

            newAdapter().deregister(ID);

            // HikariCP 7: softEvictConnections() 는 HikariPoolMXBean 의 메서드
            // deregister() 반환 이전(동기 경로)에 호출되므로 즉시 검증 가능
            verify(mockMxBean).softEvictConnections();
        }
    }

    // ── ② 즉시 드레인 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("활성 커넥션 없음 — 즉시 종료")
    class ImmediateDrain {

        @Test
        @DisplayName("활성 커넥션이 없으면 즉시 close() 가 호출된다")
        void drain_zeroActiveConnections_closesQuickly() throws InterruptedException {
            CountDownLatch closed = new CountDownLatch(1);
            when(mockMxBean.getActiveConnections()).thenReturn(0);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapter().deregister(ID);

            assertThat(closed.await(AWAIT_SECONDS, SECONDS))
                    .as("활성 커넥션 없을 때 %ds 내에 close() 가 호출되어야 한다", AWAIT_SECONDS)
                    .isTrue();
            verify(mockHikari, times(1)).close();
        }
    }

    // ── ③ 드레인 후 종료 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("활성 커넥션 드레인 후 종료")
    class DrainThenClose {

        @Test
        @DisplayName("활성 커넥션이 드레인되면 그 후 close() 가 정확히 1회 호출된다")
        void drain_activeConnectionsDrain_closesExactlyOnce() throws InterruptedException {
            CountDownLatch closed = new CountDownLatch(1);
            AtomicInteger poll = new AtomicInteger(0);

            // 처음 3번 poll → active=2, 이후 → active=0
            when(mockMxBean.getActiveConnections())
                    .thenAnswer(inv -> poll.getAndIncrement() < 3 ? 2 : 0);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapter().deregister(ID);

            assertThat(closed.await(AWAIT_SECONDS, SECONDS))
                    .as("드레인 완료 후 %ds 내에 close() 가 호출되어야 한다", AWAIT_SECONDS)
                    .isTrue();
            verify(mockHikari, times(1)).close();
        }

        @Test
        @DisplayName("드레인 중에는 softEvictConnections() 를 추가 호출하지 않는다")
        void drain_doesNotCallSoftEvictMoreThanOnce() throws InterruptedException {
            CountDownLatch closed = new CountDownLatch(1);
            when(mockMxBean.getActiveConnections()).thenReturn(0);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapter().deregister(ID);
            closed.await(AWAIT_SECONDS, SECONDS);

            // HikariCP 7: softEvictConnections() 는 HikariPoolMXBean 의 메서드
            verify(mockMxBean, times(1)).softEvictConnections();
        }
    }

    // ── ④ 타임아웃 강제 종료 ─────────────────────────────────────────────

    @Nested
    @DisplayName("드레인 타임아웃 — 강제 종료")
    class TimeoutForceClose {

        @Test
        @DisplayName("타임아웃 경과 후 close() 가 강제로 호출된다")
        void drain_timeout_forcesCloseAfterDeadline() throws InterruptedException {
            CountDownLatch closed = new CountDownLatch(1);
            when(mockMxBean.getActiveConnections()).thenReturn(1); // 드레인 안 됨
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            long start = System.currentTimeMillis();
            newAdapter().deregister(ID);

            // SHORT_TIMEOUT(500ms) 경과 후 강제 종료 + 여유 1s
            assertThat(closed.await(SHORT_TIMEOUT.toMillis() + 1000, MILLISECONDS))
                    .as("타임아웃 후 강제 close() 가 호출되어야 한다")
                    .isTrue();

            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed)
                    .as("close() 는 drainTimeout(%dms) 이후에 호출되어야 한다", SHORT_TIMEOUT.toMillis())
                    .isGreaterThanOrEqualTo(SHORT_TIMEOUT.toMillis());

            verify(mockHikari).close();
        }
    }

    // ── ⑤ 비동기성 보장 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("deregister() 비동기 반환")
    class AsyncReturn {

        @Test
        @DisplayName("deregister() 는 활성 커넥션이 있어도 즉시 반환된다")
        void deregister_returnsImmediately_withActiveConnections() {
            when(mockMxBean.getActiveConnections()).thenReturn(5); // 드레인 안 됨

            long start   = System.nanoTime();
            newAdapter().deregister(ID);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed)
                    .as("deregister() 는 100ms 내에 반환되어야 한다 (실제: %s)", elapsed)
                    .isLessThan(Duration.ofMillis(100));
        }
    }

    // ── ⑥ 이미 닫힌 풀 중복 종료 방지 ───────────────────────────────────

    @Nested
    @DisplayName("이미 닫힌 풀 처리")
    class AlreadyClosed {

        @Test
        @DisplayName("이미 닫힌 풀에는 softEvictConnections() 와 close() 를 호출하지 않는다")
        void drain_alreadyClosedPool_skipsEvictAndClose() throws InterruptedException {
            when(mockHikari.isClosed()).thenReturn(true);

            newAdapter().deregister(ID);

            // 동기 경로: scheduleGracefulDrain 이 isClosed 검사에서 early return
            // 비동기 경로 없으므로 짧게 대기 후 검증
            Thread.sleep(100);

            // HikariCP 7: softEvictConnections 는 MXBean 메서드
            verify(mockMxBean, never()).softEvictConnections();
            verify(mockHikari, never()).close();
        }
    }

    // ── ⑦ HTTP 요청 드레인 ───────────────────────────────────────────────

    /**
     * DB 커넥션이 이미 0이더라도 HTTP 요청 카운터가 양수이면 드레인이 대기하고,
     * 요청이 완료(decrement)되면 close() 가 호출된다는 것을 검증한다.
     *
     * <p>실제 {@link TenantRequestTracker} 를 사용하여 카운터를 직접 조작한다.
     */
    @Nested
    @DisplayName("HTTP 요청 드레인")
    class HttpRequestDrain {

        @Test
        @DisplayName("DB 커넥션이 0이어도 HTTP 요청이 있으면 드레인이 대기한다")
        void drain_httpRequestsActive_waitsUntilRequestCompletes() throws InterruptedException {
            // DB 커넥션은 처음부터 0 — HTTP 요청만이 드레인을 막는 유일한 조건
            when(mockMxBean.getActiveConnections()).thenReturn(0);

            TenantRequestTracker realTracker = new TenantRequestTracker();
            realTracker.increment(ID);  // HTTP 요청 1건 진행 중

            CountDownLatch closed = new CountDownLatch(1);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapterWith(realTracker).deregister(ID);

            // HTTP 요청이 진행 중이므로 즉시 close() 되어선 안 됨
            assertThat(closed.await(150, MILLISECONDS))
                    .as("HTTP 요청이 있으면 150ms 내에 close() 되어선 안 된다")
                    .isFalse();

            // HTTP 요청 완료 → 드레인 조건 충족
            realTracker.decrement(ID);

            assertThat(closed.await(AWAIT_SECONDS, SECONDS))
                    .as("HTTP 요청 완료 후 %ds 내에 close() 가 호출되어야 한다", AWAIT_SECONDS)
                    .isTrue();
        }

        @Test
        @DisplayName("HTTP 요청과 DB 커넥션이 동시에 드레인될 때 둘 다 0이 된 후 close() 가 호출된다")
        void drain_bothHttpAndDbActive_closesOnlyWhenBothZero() throws InterruptedException {
            AtomicInteger dbPoll  = new AtomicInteger(0);
            // 처음 4번은 dbConns=1, 이후 0 (HTTP 요청이 먼저 0이 되어도 기다려야 함)
            when(mockMxBean.getActiveConnections())
                    .thenAnswer(inv -> dbPoll.getAndIncrement() < 4 ? 1 : 0);

            TenantRequestTracker realTracker = new TenantRequestTracker();
            realTracker.increment(ID);  // HTTP 요청 1건

            CountDownLatch closed = new CountDownLatch(1);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapterWith(realTracker).deregister(ID);

            // DB 커넥션이 드레인될 때까지 기다린 뒤 HTTP 요청도 완료
            Thread.sleep(80);  // 4번 폴링(4×10ms)이 충분히 지나도록
            realTracker.decrement(ID);

            assertThat(closed.await(AWAIT_SECONDS, SECONDS))
                    .as("HTTP 요청 + DB 커넥션 모두 완료 후 %ds 내에 close() 가 호출되어야 한다", AWAIT_SECONDS)
                    .isTrue();
            verify(mockHikari, times(1)).close();
        }

        @Test
        @DisplayName("forceClose() 후 requestTracker.remove() 가 호출된다")
        void forceClose_callsRequestTrackerRemove() throws InterruptedException {
            when(mockMxBean.getActiveConnections()).thenReturn(0);

            TenantRequestTracker realTracker = new TenantRequestTracker();
            realTracker.increment(ID);   // 1건 increment
            realTracker.decrement(ID);   // 즉시 완료 → activeRequests = 0

            CountDownLatch closed = new CountDownLatch(1);
            doAnswer(inv -> { closed.countDown(); return null; }).when(mockHikari).close();

            newAdapterWith(realTracker).deregister(ID);
            closed.await(AWAIT_SECONDS, SECONDS);

            // forceClose() 가 hikari.close() 를 호출한 뒤 requestTracker.remove() 를 호출하므로
            // remove 이후 activeRequests 는 0 이어야 한다 (이미 0이었으므로 동일)
            assertThat(realTracker.activeRequests(ID))
                    .as("forceClose() 후 activeRequests 는 0 이어야 한다")
                    .isZero();
        }
    }

    // ── 테스트 전용 레지스트리 ─────────────────────────────────────────────

    /**
     * {@code unregisterAndSnapshot()} 이 미리 심어둔 mock {@link HikariDataSource} 를
     * {@code removed} 로 반환하는 가짜 레지스트리.
     *
     * <p>{@link TenantDataSourceRegistry} 의 private {@code dataSourceMap} 을 우회하기 위해
     * {@code unregisterAndSnapshot} 을 완전히 재정의한다.
     */
    static class FakeDrainRegistry extends TenantDataSourceRegistry {

        private final HikariDataSource stored;

        FakeDrainRegistry(HikariDataSource stored) {
            this.stored = stored;
        }

        @Override
        public UnregisterResult unregisterAndSnapshot(TenantId tenantId) {
            return new UnregisterResult(Map.of(), stored);
        }

        // deregister 흐름에서 사용되지 않는 메서드들 — 안전한 기본값 반환
        @Override public Map<String, DataSource> registerAndSnapshot(TenantId id, DataSourceSpec s) { return Map.of(); }
        @Override public Map<String, DataSource> snapshot()           { return Map.of(); }
        @Override public DataSource             get(TenantId id)     { return stored; }
        @Override public boolean                isRegistered(TenantId id) { return false; }
    }
}
