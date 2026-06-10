package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.exception.TenantContextMissingException;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테넌트별 DataSource 라우팅 관리.
 *
 * <h2>라우팅 규칙</h2>
 * <ol>
 *   <li>읽기 전용 트랜잭션({@code @Transactional(readOnly = true)})이고
 *       slave DataSource 가 하나 이상 등록된 경우 → round-robin 으로 slave 중 하나 선택</li>
 *   <li>그 외 모든 경우(쓰기 트랜잭션 또는 slave 미구성) → {@code "tenantId:master"} 키로 라우팅</li>
 * </ol>
 *
 * <h2>round-robin 구현</h2>
 * 테넌트별 {@link AtomicLong} 카운터를 증가시키며 slave 키 목록의 인덱스를 순환한다.
 * 카운터는 long 범위를 초과해도 자연스럽게 wrap-around 되므로 별도 리셋이 불필요하다.
 *
 * <h2>LazyConnectionDataSourceProxy 와의 연동</h2>
 * {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy}가 감싸고 있어
 * 트랜잭션 시작 시점이 아닌 실제 SQL 실행 직전에 커넥션을 획득한다.
 * 이 덕분에 {@code @Transactional(readOnly = true)} 플래그가 설정된 뒤
 * {@code determineCurrentLookupKey()} 가 호출되므로 readOnly 여부를 안정적으로 읽을 수 있다.
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private final TenantContextHolder contextHolder;

    /**
     * refresh() 호출 시 갱신.
     * 테넌트 ID → 정렬된 slave 키 목록 (ordinal 순).
     * 빈 리스트면 slave 미구성.
     */
    private volatile Map<String, List<String>> slaveKeysByTenant = Map.of();

    /** 테넌트별 round-robin 카운터. slave 라우팅 시 슬롯 선택에 사용. */
    private final ConcurrentHashMap<String, AtomicLong> slaveCounters = new ConcurrentHashMap<>();

    public RoutingDataSource(TenantContextHolder contextHolder) {
        this.contextHolder = contextHolder;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (!contextHolder.hasTenant()) {
            throw new TenantContextMissingException();
        }
        String tenantId = contextHolder.getTenant().value();
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        if (isReadOnly) {
            List<String> slaveKeys = slaveKeysByTenant.getOrDefault(tenantId, List.of());
            if (!slaveKeys.isEmpty()) {
                AtomicLong counter = slaveCounters.computeIfAbsent(tenantId, k -> new AtomicLong(0));
                int idx = (int) (Math.abs(counter.getAndIncrement()) % slaveKeys.size());
                return slaveKeys.get(idx);
            }
        }
        return tenantId + ":master";
    }

    public void refresh(Map<String, DataSource> dataSourceMap) {
        // slave 키를 테넌트별로 그룹화하여 캐싱 (ordinal 순 정렬 보장)
        Map<String, List<String>> byTenant = new java.util.HashMap<>();
        for (String key : dataSourceMap.keySet()) {
            int slaveIdx = key.indexOf(":slave:");
            if (slaveIdx >= 0) {
                String tenantId = key.substring(0, slaveIdx);
                byTenant.computeIfAbsent(tenantId, k -> new java.util.ArrayList<>()).add(key);
            }
        }
        byTenant.replaceAll((k, v) -> List.copyOf(v.stream().sorted().toList()));
        this.slaveKeysByTenant = Map.copyOf(byTenant);

        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
