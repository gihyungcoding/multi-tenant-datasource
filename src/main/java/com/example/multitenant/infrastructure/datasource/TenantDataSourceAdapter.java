package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry.UnregisterResult;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 테넌트 DataSource 등록/해제 어댑터.
 *
 * <h2>Graceful Drain (비동기 풀 종료)</h2>
 * {@link #deregister} 는 3단계로 처리된다.
 * <ol>
 *   <li><b>즉시 차단</b>(동기): 라우팅 테이블 갱신 → 신규 요청은 즉시 거부</li>
 *   <li><b>소프트 퇴거</b>(동기): {@code softEvictConnections()} → 유휴 커넥션 즉시 반납,
 *       사용 중 커넥션은 "반환 시 종료" 플래그 설정</li>
 *   <li><b>비동기 드레인</b>(Virtual Thread): 진행 중인 HTTP 요청과 DB 커넥션이
 *       모두 0이 될 때까지 폴링 후 종료. {@link #DRAIN_TIMEOUT} 초과 시 강제 종료.</li>
 * </ol>
 *
 * <h2>드레인 종료 조건</h2>
 * {@link #waitUntilDrained} 는 두 가지 카운터가 동시에 0이 될 때 종료한다.
 * <ul>
 *   <li>HTTP 요청 카운터: {@link TenantRequestTracker#activeRequests} — 진행 중인 HTTP 요청</li>
 *   <li>DB 커넥션 카운터: {@link HikariPoolMXBean#getActiveConnections} — 사용 중인 DB 커넥션</li>
 * </ul>
 * HTTP 요청이 먼저 완료되면 DB 커넥션도 반납되므로 두 조건이 함께 0이 되는 것이 정상이다.
 * 어느 쪽이든 먼저 드레인된다면 나머지가 0이 될 때까지 계속 대기한다.
 *
 * <h2>synchronized 범위</h2>
 * {@code deregister} 의 {@code synchronized} 블록은 라우팅 갱신까지만 보호하고
 * 비동기 드레인 태스크 <em>예약</em> 후 즉시 반환된다.
 * 실제 드레인 루프와 {@code close()} 는 락 밖 Virtual Thread 에서 실행된다.
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceAdapter implements TenantDataSourcePort {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceAdapter.class);

    /** 드레인 대기 최대 시간. 초과 시 강제 종료. */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);
    /** 드레인 폴링 간격 */
    static final Duration POLL_INTERVAL  = Duration.ofMillis(200);

    private final TenantDataSourceRegistry registry;
    private final RoutingDataSource        routingDataSource;
    private final TenantSchemaInitializer  schemaInitializer;
    private final TenantRequestTracker     requestTracker;
    private final ExecutorService          drainExecutor;
    private final Duration                 drainTimeout;
    private final Duration                 pollInterval;

    /** 운영용 생성자 — Spring Bean 주입. 다중 생성자 환경에서 @Autowired 로 우선 선택. */
    @Autowired
    public TenantDataSourceAdapter(TenantDataSourceRegistry registry,
                                   RoutingDataSource routingDataSource,
                                   TenantSchemaInitializer schemaInitializer,
                                   TenantRequestTracker requestTracker) {
        this(registry, routingDataSource, schemaInitializer, requestTracker,
             Executors.newVirtualThreadPerTaskExecutor(),
             DRAIN_TIMEOUT,
             POLL_INTERVAL);
    }

    /** 테스트용 생성자 — executor / timeout / requestTracker 주입 가능 */
    public TenantDataSourceAdapter(TenantDataSourceRegistry registry,
                                   RoutingDataSource routingDataSource,
                                   TenantSchemaInitializer schemaInitializer,
                                   TenantRequestTracker requestTracker,
                                   ExecutorService drainExecutor,
                                   Duration drainTimeout,
                                   Duration pollInterval) {
        this.registry          = registry;
        this.routingDataSource = routingDataSource;
        this.schemaInitializer = schemaInitializer;
        this.requestTracker    = requestTracker;
        this.drainExecutor     = drainExecutor;
        this.drainTimeout      = drainTimeout;
        this.pollInterval      = pollInterval;
    }

    /**
     * 테넌트 DataSource 를 등록하고 라우팅 테이블을 갱신한다.
     *
     * <p><b>왜 synchronized 인가</b><br>
     * {@link TenantDataSourceRegistry#registerAndSnapshot} 은 등록과 스냅샷 포착을 원자적으로
     * 수행하지만, {@link RoutingDataSource#refresh} 는 별도 호출이다.
     * synchronized 없이는 stale snapshot 이 나중에 적용되어 다른 테넌트의 라우팅을 덮어쓸 수 있다.
     */
    @Override
    public synchronized void register(TenantId tenantId, DataSourceSpec spec) {
        Map<String, DataSource> snapshot = registry.registerAndSnapshot(tenantId, spec);
        schemaInitializer.initialize(registry.get(tenantId));
        routingDataSource.refresh(snapshot);
    }

    /**
     * 테넌트 DataSource 를 라우팅 테이블에서 즉시 제거하고,
     * 진행 중인 HTTP 요청과 DB 커넥션이 완료된 후 HikariCP 풀을 비동기로 종료한다.
     *
     * <pre>
     *   ① unregisterAndSnapshot()         — 레지스트리 원자적 제거
     *   ② routingDataSource.refresh()     — 신규 요청 즉시 거부 (동기)
     *   ③ softEvictConnections()           — 유휴 커넥션 즉시 반납  (동기)
     *   ④ drainExecutor 태스크 예약        — synchronized 블록 반환
     *      └─ [Virtual Thread]
     *           while (httpRequests > 0 || dbConns > 0 &amp;&amp; !timeout) { park }
     *           hikari.close()
     *           requestTracker.remove()
     * </pre>
     */
    @Override
    public synchronized void deregister(TenantId tenantId) {
        UnregisterResult result = registry.unregisterAndSnapshot(tenantId);
        routingDataSource.refresh(result.snapshot());       // ① 신규 요청 즉시 차단
        scheduleGracefulDrain(tenantId, result.removed()); // ② 비동기 드레인 예약 후 즉시 반환
    }

    @Override
    public boolean isRegistered(TenantId tenantId) {
        return registry.isRegistered(tenantId);
    }

    @Override
    public void refreshRoutingTable() {
        routingDataSource.refresh(registry.snapshot());
    }

    // ── 비동기 드레인 ─────────────────────────────────────────────────────────

    /**
     * 유휴 커넥션을 즉시 회수하고, Virtual Thread 에서 드레인 루프를 시작한다.
     *
     * <p>이 메서드는 {@code synchronized deregister} 안에서 호출되지만,
     * {@code drainExecutor.execute()} 는 태스크를 큐에 넣고 즉시 반환한다.
     * 드레인 루프는 락 밖에서 실행되므로 다른 등록/해제 요청을 차단하지 않는다.
     */
    private void scheduleGracefulDrain(TenantId tenantId, DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.isClosed()) return;

        HikariPoolMXBean mxBean = hikari.getHikariPoolMXBean();
        if (mxBean == null) {
            // 풀이 아직 초기화되지 않았거나 이미 종료된 경우 — 바로 close
            forceClose(tenantId, hikari);
            return;
        }

        // HikariCP 7: softEvictConnections() 는 HikariPoolMXBean 의 메서드로 이동
        // 유휴 커넥션 즉시 반납 + 사용 중 커넥션은 "반환 시 종료" 플래그 설정
        mxBean.softEvictConnections();
        log.info("드레인 시작: tenantId={}, httpRequests={}, dbConnections={}",
                 tenantId.value(),
                 requestTracker.activeRequests(tenantId),
                 mxBean.getActiveConnections());

        drainExecutor.execute(() -> {
            try {
                waitUntilDrained(tenantId, hikari);
            } finally {
                forceClose(tenantId, hikari);  // 드레인 완료 또는 타임아웃 시 반드시 종료
            }
        });
    }

    /**
     * HTTP 요청 카운터와 DB 활성 커넥션 수가 모두 0이 될 때까지 폴링한다.
     *
     * <p>타임아웃 만료 시 경고 로그만 남기고 반환한다. 실제 종료는 {@code forceClose()} 에서 처리한다.
     */
    private void waitUntilDrained(TenantId tenantId, HikariDataSource hikari) {
        long deadline = System.nanoTime() + drainTimeout.toNanos();

        while (System.nanoTime() < deadline) {
            int httpRequests = requestTracker.activeRequests(tenantId);
            int dbConns      = activeConnections(hikari);

            if (httpRequests == 0 && dbConns == 0) {
                log.info("드레인 완료: tenantId={}", tenantId.value());
                return;
            }
            log.debug("드레인 중: tenantId={}, httpRequests={}, dbConnections={}",
                      tenantId.value(), httpRequests, dbConns);
            LockSupport.parkNanos(pollInterval.toNanos());
        }
        log.warn("드레인 타임아웃({}s): tenantId={} — 강제 종료",
                 drainTimeout.toSeconds(), tenantId.value());
    }

    private void forceClose(TenantId tenantId, HikariDataSource hikari) {
        if (!hikari.isClosed()) {
            hikari.close();
            log.info("HikariCP 풀 종료: tenantId={}, pool={}", tenantId.value(), hikari.getPoolName());
        }
        // HTTP 요청 카운터 항목 제거 — 메모리 누수 방지
        requestTracker.remove(tenantId);
    }

    /** MXBean 이 null 인 엣지 케이스 방어 (풀 초기화 전 또는 종료 후) */
    private static int activeConnections(HikariDataSource hikari) {
        HikariPoolMXBean mxBean = hikari.getHikariPoolMXBean();
        return (mxBean != null) ? mxBean.getActiveConnections() : 0;
    }

    /**
     * 애플리케이션 종료 시 진행 중인 드레인 작업이 완료될 때까지 대기한다.
     *
     * <p>Spring의 {@code Lifecycle} 처리 후에 호출되므로, 이 시점에는 이미
     * 신규 요청이 차단된 상태이다. 잔여 드레인 태스크를 완전히 끝낸 후 JVM 이 종료된다.
     */
    @PreDestroy
    public void shutdownDrainExecutor() {
        drainExecutor.shutdown();
        try {
            if (!drainExecutor.awaitTermination(drainTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("드레인 Executor 타임아웃 — 강제 종료");
                drainExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            drainExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
