package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantContextHolderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link TenantContextHolderImpl} ThreadLocal 격리 단위 테스트.
 * I/O 없음. Spring 컨텍스트 없음.
 */
@SmallTest
@DisplayName("TenantContextHolderImpl")
class TenantContextHolderImplTest {

    /** 실제 인스턴스 — Spring 없이 직접 생성 */
    private final TenantContextHolderImpl holder = new TenantContextHolderImpl();

    @AfterEach
    void tearDown() {
        holder.clear();
    }

    // ── 초기 상태 ──────────────────────────────────────────────

    @Test
    @DisplayName("초기 상태에서 hasTenant() 는 false 를 반환한다")
    void initial_hasTenantIsFalse() {
        assertThat(holder.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("초기 상태에서 getTenant() 는 null 을 반환한다")
    void initial_getTenantIsNull() {
        assertThat(holder.getTenant()).isNull();
    }

    // ── setTenant / getTenant ──────────────────────────────────

    @Test
    @DisplayName("setTenant() 후 getTenant() 는 동일한 TenantId 를 반환한다")
    void setTenant_getTenant_returnsSameId() {
        TenantId id = new TenantId("tenant-x");
        holder.setTenant(id);
        assertThat(holder.getTenant()).isEqualTo(id);
    }

    @Test
    @DisplayName("setTenant() 후 hasTenant() 는 true 를 반환한다")
    void setTenant_hasTenantIsTrue() {
        holder.setTenant(new TenantId("tenant-x"));
        assertThat(holder.hasTenant()).isTrue();
    }

    @Test
    @DisplayName("setTenant() 을 두 번 호출하면 마지막 값으로 덮어쓴다")
    void setTenant_twice_lastValueWins() {
        holder.setTenant(new TenantId("first"));
        holder.setTenant(new TenantId("second"));
        assertThat(holder.getTenant().value()).isEqualTo("second");
    }

    // ── clear() ───────────────────────────────────────────────

    @Test
    @DisplayName("clear() 호출 후 hasTenant() 는 false 가 된다")
    void clear_hasTenantIsFalse() {
        holder.setTenant(new TenantId("tenant-x"));
        holder.clear();
        assertThat(holder.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("clear() 호출 후 getTenant() 는 null 이 된다")
    void clear_getTenantIsNull() {
        holder.setTenant(new TenantId("tenant-x"));
        holder.clear();
        assertThat(holder.getTenant()).isNull();
    }

    @Test
    @DisplayName("컨텍스트 없는 상태에서 clear() 를 호출해도 예외가 발생하지 않는다")
    void clear_whenEmpty_noException() {
        assertThatCode(() -> holder.clear()).doesNotThrowAnyException();
    }

    // ── Virtual Thread 격리 ────────────────────────────────────

    @Test
    @DisplayName("Virtual Thread 100개 동시 실행 시 각 스레드의 컨텍스트는 서로 격리된다")
    void virtualThreads_tenantContextIsIsolated() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> capturedTenants = new CopyOnWriteArrayList<>();
        AtomicInteger mismatchCount = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                String expected = "tenant-" + i;
                executor.submit(() -> {
                    try {
                        holder.setTenant(new TenantId(expected));
                        // 의도적 지연 — 다른 스레드의 set() 와 경합 유발
                        Thread.sleep(5);

                        String actual = holder.getTenant().value();
                        capturedTenants.add(actual);

                        if (!expected.equals(actual)) {
                            mismatchCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        holder.clear();
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);

        assertThat(capturedTenants).hasSize(threadCount);
        assertThat(mismatchCount.get())
                .as("컨텍스트 오염 발생 횟수는 0이어야 한다")
                .isZero();
    }
}
