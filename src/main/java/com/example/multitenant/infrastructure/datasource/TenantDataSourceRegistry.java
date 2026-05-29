package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 멀티테넌트 DataSource 등록기
 *
 * <p><b>스레드 안전성</b><br>
 * {@link #registerAndSnapshot} 은 DataSource 등록과 스냅샷 포착을 원자적으로 수행한다.
 * 이를 통해 다음 race condition 을 원천 차단한다.
 * <pre>
 *   [버그 패턴]
 *   Thread A: register("a") → snapshot = {a}     ← stale
 *   Thread B: register("b") → snapshot = {a, b}
 *   Thread B: refresh({a, b}) ✓
 *   Thread A: refresh({a})   ✗  → b 소멸
 *
 *   [수정 후]
 *   Thread A: synchronized { register("a"); snapshot = {a} }
 *   Thread B: synchronized { register("b"); snapshot = {a, b} }
 *   → 두 refresh 가 어떤 순서로 실행되든 나중 snapshot 이 항상 전체를 포함
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
     * <p>{@code synchronized} 블록 안에서 put 과 copyOf 를 연속 실행하므로
     * 두 작업 사이에 다른 스레드가 끼어들어 stale snapshot 이 생성되는 것을 방지한다.
     *
     * <p>HikariDataSource 생성은 시간이 걸리므로 lock 범위를 최소화하기 위해
     * 먼저 생성한 뒤 임계 영역에서 등록한다.
     *
     * @return 이 등록을 포함한 현재 전체 DataSource 맵의 불변 스냅샷
     */
    public Map<String, DataSource> registerAndSnapshot(TenantId tenantId, DataSourceSpec spec) {
        DataSource dataSource = createDataSource(tenantId, spec); // lock 밖에서 생성 (시간 소요)
        synchronized (this) {
            dataSourceMap.put(tenantId.value(), dataSource);
            return Map.copyOf(dataSourceMap);               // put + copyOf 가 원자적
        }
    }

    public boolean isRegistered(TenantId tenantId) {
        return dataSourceMap.containsKey(tenantId.value());
    }

    // RoutingDataSource 초기 로딩(SmartInitializingSingleton) 전용
    public Map<String, DataSource> snapshot() {
        return Map.copyOf(dataSourceMap);
    }

    private HikariDataSource createDataSource(TenantId tenantId, DataSourceSpec spec) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.url());
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setPoolName("HikariPool-" + tenantId.value());
        return new HikariDataSource(config);
    }
}
