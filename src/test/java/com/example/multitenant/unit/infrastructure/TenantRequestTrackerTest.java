package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantRequestTracker} 단위 테스트.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>초기 카운터: 0</li>
 *   <li>increment → activeRequests 증가</li>
 *   <li>decrement → activeRequests 감소</li>
 *   <li>존재하지 않는 테넌트에 decrement 호출 시 예외 없음</li>
 *   <li>remove → 카운터 항목 삭제 (이후 activeRequests = 0)</li>
 *   <li>여러 테넌트 독립 카운팅</li>
 *   <li>동시 increment/decrement 스레드 안전성</li>
 * </ul>
 */
@SmallTest
@DisplayName("TenantRequestTracker")
class TenantRequestTrackerTest {

    private static final TenantId ID_A = new TenantId("tenant-a");
    private static final TenantId ID_B = new TenantId("tenant-b");

    private TenantRequestTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TenantRequestTracker();
    }

    // ── 기본 동작 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("초기 상태")
    class InitialState {

        @Test
        @DisplayName("등록된 적 없는 테넌트의 activeRequests 는 0이다")
        void activeRequests_unknownTenant_returnsZero() {
            assertThat(tracker.activeRequests(ID_A)).isZero();
        }
    }

    @Nested
    @DisplayName("increment")
    class Increment {

        @Test
        @DisplayName("increment() 호출 후 activeRequests 가 1 증가한다")
        void increment_once_returnsOne() {
            tracker.increment(ID_A);
            assertThat(tracker.activeRequests(ID_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("increment() 3회 호출 후 activeRequests 는 3이다")
        void increment_thrice_returnsThree() {
            tracker.increment(ID_A);
            tracker.increment(ID_A);
            tracker.increment(ID_A);
            assertThat(tracker.activeRequests(ID_A)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("decrement")
    class Decrement {

        @Test
        @DisplayName("increment 후 decrement 하면 activeRequests 가 0이 된다")
        void decrement_afterIncrement_returnsZero() {
            tracker.increment(ID_A);
            tracker.decrement(ID_A);
            assertThat(tracker.activeRequests(ID_A)).isZero();
        }

        @Test
        @DisplayName("카운터가 없는 테넌트에 decrement 를 호출해도 예외가 발생하지 않는다")
        void decrement_unknownTenant_noException() {
            org.assertj.core.api.Assertions.assertThatCode(() -> tracker.decrement(ID_A))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("remove() 후 activeRequests 는 0이 된다")
        void remove_afterIncrement_returnsZero() {
            tracker.increment(ID_A);
            tracker.increment(ID_A);
            tracker.remove(ID_A);
            assertThat(tracker.activeRequests(ID_A)).isZero();
        }

        @Test
        @DisplayName("존재하지 않는 테넌트에 remove() 를 호출해도 예외가 발생하지 않는다")
        void remove_unknownTenant_noException() {
            org.assertj.core.api.Assertions.assertThatCode(() -> tracker.remove(ID_A))
                    .doesNotThrowAnyException();
        }
    }

    // ── 테넌트 격리 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("테넌트 격리")
    class TenantIsolation {

        @Test
        @DisplayName("서로 다른 테넌트의 카운터는 독립적으로 관리된다")
        void differentTenants_haveIndependentCounters() {
            tracker.increment(ID_A);
            tracker.increment(ID_A);
            tracker.increment(ID_B);

            assertThat(tracker.activeRequests(ID_A)).isEqualTo(2);
            assertThat(tracker.activeRequests(ID_B)).isEqualTo(1);
        }

        @Test
        @DisplayName("한 테넌트를 remove 해도 다른 테넌트의 카운터에 영향이 없다")
        void remove_oneTenant_doesNotAffectOther() {
            tracker.increment(ID_A);
            tracker.increment(ID_B);

            tracker.remove(ID_A);

            assertThat(tracker.activeRequests(ID_A)).isZero();
            assertThat(tracker.activeRequests(ID_B)).isEqualTo(1);
        }
    }

    // ── 동시성 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("동시성 — 스레드 안전성")
    class Concurrency {

        @Test
        @DisplayName("50개 Virtual Thread 동시 increment/decrement 후 카운터는 0이다")
        void concurrent50Threads_incrementAndDecrement_counterIsZero() throws InterruptedException {
            int threads = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads * 2);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // 50개 increment
                for (int i = 0; i < threads; i++) {
                    executor.submit(() -> {
                        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        tracker.increment(ID_A);
                        done.countDown();
                    });
                }
                // 50개 decrement (increment 완료를 위해 약간의 여유를 두지 않고 동시 실행)
                for (int i = 0; i < threads; i++) {
                    executor.submit(() -> {
                        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        tracker.increment(ID_A);
                        tracker.decrement(ID_A);
                        done.countDown();
                    });
                }

                start.countDown();
                assertThat(done.await(10, SECONDS)).isTrue();
            }

            // 50번 increment(첫 배치) + 50번 increment - 50번 decrement(두 번째 배치) = 50
            assertThat(tracker.activeRequests(ID_A)).isEqualTo(50);
        }

        @Test
        @DisplayName("N번 increment 후 N번 decrement를 동시에 하면 최종 카운터는 0이다")
        void concurrent_equalIncrementAndDecrement_counterIsZero() throws InterruptedException {
            int n = 100;
            AtomicInteger errors = new AtomicInteger(0);

            // 먼저 N번 increment
            for (int i = 0; i < n; i++) tracker.increment(ID_A);
            assertThat(tracker.activeRequests(ID_A)).isEqualTo(n);

            // 동시에 N번 decrement
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(n);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < n; i++) {
                    executor.submit(() -> {
                        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        tracker.decrement(ID_A);
                        done.countDown();
                    });
                }

                start.countDown();
                assertThat(done.await(10, SECONDS)).isTrue();
            }

            assertThat(errors.get()).isZero();
            assertThat(tracker.activeRequests(ID_A)).isZero();
        }
    }
}
