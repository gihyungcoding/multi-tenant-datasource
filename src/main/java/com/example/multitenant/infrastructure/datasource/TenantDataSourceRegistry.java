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
import java.util.stream.IntStream;

/**
 * 멀티테넌트 DataSource 등록기.
 *
 * <h2>키 체계</h2>
 * <ul>
 *   <li>{@code "tenant-a:master"}   — 쓰기 트랜잭션용 master DataSource</li>
 *   <li>{@code "tenant-a:slave:0"}  — 읽기 전용 트랜잭션용 slave DataSource (0번째)</li>
 *   <li>{@code "tenant-a:slave:1"}  — 두 번째 slave DataSource</li>
 * </ul>
 * slave 는 옵션이다. 등록하지 않으면 {@code "tenant-a:slave:*"} 키가 맵에 존재하지 않는다.
 *
 * <h2>스레드 안전성</h2>
 * {@link #registerAndSnapshot} 은 DataSource 등록과 스냅샷 포착을 원자적으로 수행한다.
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantDataSourceRegistry {

    private final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

    /**
     * 테넌트 DataSource(master + 복수 slave) 를 등록하고 전체 맵의 스냅샷을 원자적으로 반환한다.
     *
     * <p>HikariDataSource 생성은 시간이 걸리므로 lock 범위를 최소화하기 위해
     * 먼저 생성한 뒤 임계 영역에서 등록한다.
     *
     * @param masterSpec  master DataSource 접속 정보
     * @param slaveSpecs  slave DataSource 접속 정보 목록. 비어있으면 master 단독 운영.
     * @return 이 등록을 포함한 현재 전체 DataSource 맵의 불변 스냅샷
     */
    public Map<String, DataSource> registerAndSnapshot(TenantId tenantId,
                                                       DataSourceSpec masterSpec,
                                                       List<DataSourceSpec> slaveSpecs) {
        DataSource masterDs = createDataSource(masterKey(tenantId), masterSpec);
        List<DataSource> slaveDsList = IntStream.range(0, slaveSpecs.size())
                .mapToObj(i -> (DataSource) createDataSource(slaveKey(tenantId, i), slaveSpecs.get(i)))
                .toList();

        synchronized (this) {
            dataSourceMap.put(masterKey(tenantId), masterDs);
            for (int i = 0; i < slaveDsList.size(); i++) {
                dataSourceMap.put(slaveKey(tenantId, i), slaveDsList.get(i));
            }
            return Map.copyOf(dataSourceMap);
        }
    }

    /**
     * 테넌트 DataSource(master + 모든 slave) 를 제거하고 제거 후 맵의 스냅샷을 원자적으로 반환한다.
     *
     * @return 제거 후 스냅샷 + 제거된 DataSource 목록 (master + 모든 slave)
     */
    public UnregisterResult unregisterAndSnapshot(TenantId tenantId) {
        synchronized (this) {
            List<DataSource> removed = new ArrayList<>();

            DataSource master = dataSourceMap.remove(masterKey(tenantId));
            if (master != null) removed.add(master);

            // slave 키 전체 탐색하여 제거
            String slavePrefix = tenantId.value() + ":slave:";
            List<String> slaveKeys = dataSourceMap.keySet().stream()
                    .filter(k -> k.startsWith(slavePrefix))
                    .toList();
            for (String key : slaveKeys) {
                DataSource ds = dataSourceMap.remove(key);
                if (ds != null) removed.add(ds);
            }

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
     * @param removed  제거된 DataSource 목록 (master + 모든 slave, 미등록 테넌트면 빈 리스트)
     */
    public record UnregisterResult(Map<String, DataSource> snapshot, List<DataSource> removed) {}

    // ── 키 생성 ─────────────────────────────────────────────────────────────

    static String masterKey(TenantId id)         { return id.value() + ":master"; }
    static String slaveKey(TenantId id, int idx) { return id.value() + ":slave:" + idx; }
    static String slavePrefix(TenantId id)       { return id.value() + ":slave:"; }

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
