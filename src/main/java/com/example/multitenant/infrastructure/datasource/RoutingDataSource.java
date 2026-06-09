package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.exception.TenantContextMissingException;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

/**
 * 테넌트별 DataSource 라우팅 관리.
 *
 * <h2>라우팅 규칙</h2>
 * <ol>
 *   <li>읽기 전용 트랜잭션({@code @Transactional(readOnly = true)})이고
 *       slave DataSource 가 등록된 경우 → {@code "tenantId:slave"} 키로 라우팅</li>
 *   <li>그 외 모든 경우(쓰기 트랜잭션 또는 slave 미구성) → {@code "tenantId:master"} 키로 라우팅</li>
 * </ol>
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

    /** refresh() 호출 시 갱신되는 등록 키 집합 — slave 존재 여부 판별에 사용 */
    private volatile Set<String> registeredKeys = Set.of();

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
            String slaveKey = tenantId + ":slave";
            if (registeredKeys.contains(slaveKey)) {
                return slaveKey;
            }
        }
        return tenantId + ":master";
    }

    public void refresh(Map<String, DataSource> dataSourceMap) {
        this.registeredKeys = Set.copyOf(dataSourceMap.keySet());
        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
