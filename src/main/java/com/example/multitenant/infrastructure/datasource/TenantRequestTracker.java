package com.example.multitenant.infrastructure.datasource;

import com.example.multitenant.domain.tenant.TenantId;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테넌트별 진행 중인 HTTP 요청 수를 추적하는 카운터.
 *
 * <h2>역할</h2>
 * 테넌트가 정지(suspend)될 때 진행 중인 HTTP 요청이 완료된 후
 * HikariCP 풀을 종료하기 위해, 테넌트별 in-flight 요청 수를 관리한다.
 *
 * <h2>생명주기</h2>
 * <pre>
 *   TenantInterceptor.preHandle()      → increment(tenantId)  [요청 시작]
 *   TenantInterceptor.afterCompletion() → decrement(tenantId)  [요청 종료]
 *   TenantDataSourceAdapter.drain()    → activeRequests()     [드레인 조건 확인]
 *   TenantDataSourceAdapter.forceClose() → remove(tenantId)   [풀 종료 후 정리]
 * </pre>
 *
 * <h2>스레드 안전성</h2>
 * {@link ConcurrentHashMap} + {@link AtomicInteger} 조합으로 락 없이 안전하다.
 * {@code computeIfAbsent} 의 원자성으로 카운터 생성 시 race condition 이 없다.
 *
 * @author gihyung.lee
 * @since 2026-06-02
 */
@Component
public class TenantRequestTracker {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 테넌트 요청 처리 시작. 카운터를 1 증가시킨다.
     *
     * <p>{@link com.example.multitenant.interfaces.interceptor.TenantInterceptor#preHandle}
     * 에서 테넌트 유효성 검증 성공 후 호출된다.
     */
    public void increment(TenantId tenantId) {
        counters.computeIfAbsent(tenantId.value(), k -> new AtomicInteger())
                .incrementAndGet();
    }

    /**
     * 테넌트 요청 처리 완료. 카운터를 1 감소시킨다.
     *
     * <p>{@link com.example.multitenant.interfaces.interceptor.TenantInterceptor#afterCompletion}
     * 에서 요청 성공·실패 여부와 관계없이 항상 호출된다.
     */
    public void decrement(TenantId tenantId) {
        AtomicInteger counter = counters.get(tenantId.value());
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /**
     * 현재 처리 중인 해당 테넌트의 HTTP 요청 수를 반환한다.
     *
     * <p>드레인 루프에서 이 값이 0이 될 때까지 대기한다.
     */
    public int activeRequests(TenantId tenantId) {
        AtomicInteger counter = counters.get(tenantId.value());
        return (counter != null) ? counter.get() : 0;
    }

    /**
     * 풀 종료 후 해당 테넌트의 카운터 항목을 제거한다.
     *
     * <p>메모리 누수 방지를 위해 {@code TenantDataSourceAdapter.forceClose()} 에서 호출된다.
     */
    public void remove(TenantId tenantId) {
        counters.remove(tenantId.value());
    }
}
