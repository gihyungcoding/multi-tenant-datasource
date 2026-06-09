package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 멀티테넌트 DataSource 등록기.
 *
 * <h2>키 체계</h2>
 * 각 테넌트의 DataSource 는 복합 키로 관리된다.
 * <ul>
 *   <li>{@code "tenant-a:master"} — 쓰기 트랜잭션용 master DataSource</li>
 *   <li>{@code "tenant-a:slave"}  — 읽기 전용 트랜잭션용 slave DataSource (옵션)</li>
 * </ul>
 *
 * <h2>스레드 안전성</h2>
 * {@link #registerAndSnapshot} 은 DataSource 등록과 스냅샷 포착을 원자적으로 수행한다.
 * <pre>
 *   [버그 패턴]
 *   Thread A: register("a") → snapshot = {"a:master"}     ← stale
 *   Thread B: register("b") → snapshot = {"a:master", "b:master"}
 *   Thread B: refresh({a:master, b:master}) ✓
 *   Thread A: refresh({a:master})   ✗  → b 소멸
 *
 *   [수정 후]
 *   Thread A: synchronized { register("a"); snapshot = {"a:master"} }
 *   Thread B: synchronized { register("b"); snapshot = {"a:master", "b:master"} }
 * </pre>
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceRegistry {

    private final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

    /**
     * 테넌트 DataSource 를 등록하고, 등록 완료된 전체 맵의 스냅샷을 원자적으로 반환한다.
     *
     * <p>HikariDataSource 생성은 시간이 걸리므로 lock 범위를 최소화하기 위해
     * 먼저 생성한 뒤 임계 영역에서 등록한다.
     *
     * @param masterSpec master DataSource 접속 정보
     * @param slaveSpec  slave DataSource 접속 정보. null 이면 master 단독 운영.
     * @return 이 등록을 포함한 현재 전체 DataSource 맵의 불변 스냅샷
     */
    public Map<String, DataSource> registerAndSnapshot(TenantId tenantId,
                                                       DataSourceSpec masterSpec,
                                                       DataSourceSpec slaveSpec) {
        DataSource masterDs = createDataSource(masterKey(tenantId), masterSpec);
        DataSource slaveDs  = slaveSpec != null ? createDataSource(slaveKey(tenantId), slaveSpec) : null;

        synchronized (this) {
            dataSourceMap.put(masterKey(tenantId), masterDs);
            if (slaveDs != null) {
                dataSourceMap.put(slaveKey(tenantId), slaveDs);
            }
            return Map.copyOf(dataSourceMap);
        }
    }

    /**
     * 테넌트 DataSource(master + slave)를 제거하고, 제거 후 전체 맵의 스냅샷을 원자적으로 반환한다.
     *
     * <p>제거된 DataSource 목록은 {@link UnregisterResult#removed()} 로 반환된다.
     * HikariCP 풀 종료는 호출자(어댑터)가 락 밖에서 처리한다.
     *
     * @return 제거 후 스냅샷 + 제거된 DataSource 목록
     */
    public UnregisterResult unregisterAndSnapshot(TenantId tenantId) {
        synchronized (this) {
            List<DataSource> removed = new ArrayList<>();
            DataSource master = dataSourceMap.remove(masterKey(tenantId));
            DataSource slave  = dataSourceMap.remove(slaveKey(tenantId));
            if (master != null) removed.add(master);
            if (slave  != null) removed.add(slave);
            return new UnregisterResult(Map.copyOf(dataSourceMap), List.copyOf(removed));
        }
    }

    public boolean isRegistered(TenantId tenantId) {
        return dataSourceMap.containsKey(masterKey(tenantId));
    }

    /** master DataSource 반환. 없으면 {@code null} */
    public DataSource getMaster(TenantId tenantId) {
        return dataSourceMap.get(masterKey(tenantId));
    }

    /** 현재 등록된 테넌트 수 — master 키만 카운트한다. Micrometer Gauge 에 사용. */
    public int size() {
        return (int) dataSourceMap.keySet().stream()
                .filter(k -> k.endsWith(":master"))
                .count();
    }

    public Map<String, DataSource> snapshot() {
        return Map.copyOf(dataSourceMap);
    }

    /**
     * {@link #unregisterAndSnapshot} 의 반환 타입.
     *
     * @param snapshot 제거 후 전체 DataSource 맵의 불변 스냅샷
     * @param removed  제거된 DataSource 목록 (master + slave, 미등록 테넌트면 빈 리스트)
     */
    public record UnregisterResult(Map<String, DataSource> snapshot, List<DataSource> removed) {}

    // ── 키 생성 ─────────────────────────────────────────────────────────────

    static String masterKey(TenantId id) { return id.value() + ":master"; }
    static String slaveKey(TenantId id)  { return id.value() + ":slave"; }

    // ── DataSource 생성 ──────────────────────────────────────────────────────

    private HikariDataSource createDataSource(String poolName, DataSourceSpec spec) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.url());
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setPoolName("HikariPool-" + poolName);
        return new HikariDataSource(config);
    }
}
