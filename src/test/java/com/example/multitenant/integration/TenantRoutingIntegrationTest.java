package com.example.multitenant.integration;

import com.example.multitenant.annotation.LargeTest;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import com.example.multitenant.infrastructure.exception.TenantContextMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테넌트 DataSource 라우팅 통합 테스트
 * @author gihyung.lee
 * @since 2026-05-28
 */
@LargeTest
class TenantRoutingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RegisterTenantUseCase registerTenantUseCase;

    @Autowired
    private ResolveTenantDataSourceUseCase resolveTenantDataSourceUseCase;

    @Autowired
    private RoutingDataSource routingDataSource;

    @Autowired
    private TenantContextHolder contextHolder;

    @BeforeEach
    void setUp() {
        contextHolder.clear();
    }

    // ── 테넌트 등록 ────────────────────────────────────────────

    @Test
    @DisplayName("테넌트 등록 성공")
    void registerTenant_success() {
        // when
        RegisterTenantResult result = registerTenantUseCase.register(
                new RegisterTenantCommand(
                        "tenant-a",
                        getTenantAUrl(),
                        "tenant_a",
                        "tenant_a"
                )
        );

        // then
        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("중복 테넌트 등록 시 예외 발생")
    void registerTenant_duplicate_throwsException() {
        // given
        RegisterTenantCommand command = new RegisterTenantCommand(
                "tenant-dup",
                getTenantAUrl(),
                "tenant_a",
                "tenant_a"
        );
        registerTenantUseCase.register(command);

        // when & then
        assertThatThrownBy(() -> registerTenantUseCase.register(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tenant-dup");
    }

    // ── DataSource 라우팅 ──────────────────────────────────────

    @Test
    @DisplayName("테넌트 컨텍스트 설정 후 DataSource가 라우팅된다")
    void resolveTenant_setsContext() {
        // given
        registerTenantUseCase.register(new RegisterTenantCommand(
                "tenant-route",
                getTenantAUrl(),
                "tenant_a",
                "tenant_a"
        ));

        // when
        resolveTenantDataSourceUseCase.resolve(
                new ResolveTenantCommand("tenant-route")
        );

        // then
        assertThat(contextHolder.hasTenant()).isTrue();
        assertThat(contextHolder.getTenant().value()).isEqualTo("tenant-route");

        // cleanup
        contextHolder.clear();
    }

    @Test
    @DisplayName("존재하지 않는 테넌트 resolve 시 예외 발생")
    void resolveTenant_notFound_throwsException() {
        assertThatThrownBy(() ->
                resolveTenantDataSourceUseCase.resolve(
                        new ResolveTenantCommand("not-exist")
                )
        ).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("존재하지 않는 테넌트입니다");
    }

    @Test
    @DisplayName("컨텍스트 없이 RoutingDataSource 접근 시 예외 발생")
    void routingDataSource_withoutContext_throwsException() {
        // given — 컨텍스트 없는 상태
        assertThat(contextHolder.hasTenant()).isFalse();

        // when & then — RoutingDataSource.getConnection() 직접 호출 시 예외
        // getTenantUseCase.getById() 는 masterDataSource 경유 → TenantContextMissingException 미발생
        assertThatThrownBy(() -> routingDataSource.getConnection())
                .isInstanceOf(TenantContextMissingException.class);
    }

    // ── 동시성 — 테넌트 컨텍스트 격리 ─────────────────────────

    @Test
    @DisplayName("Virtual Thread 100개 동시 실행 시 테넌트 컨텍스트가 격리된다")
    void tenantContext_isolatedAcrossVirtualThreads()
            throws InterruptedException {

        // given
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new CopyOnWriteArrayList<>();
        AtomicInteger mismatchCount = new AtomicInteger(0);

        // when
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                String expectedTenant = "thread-tenant-" + i;
                executor.submit(() -> {
                    try {
                        contextHolder.setTenant(new TenantId(expectedTenant));
                        // 의도적 지연 — 컨텍스트 오염 유발 시도
                        Thread.sleep(10);

                        String actual = contextHolder.getTenant().value();
                        results.add(actual);

                        if (!expectedTenant.equals(actual)) {
                            mismatchCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        contextHolder.clear();
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);

        // then
        assertThat(results).hasSize(threadCount);
        assertThat(mismatchCount.get())
                .as("컨텍스트 오염 발생 횟수")
                .isZero();
    }

    @Test
    @DisplayName("동시 테넌트 등록 시 중복 등록이 발생하지 않는다")
    void registerTenant_concurrent_noDuplicate()
            throws InterruptedException {

        // given
        int threadCount = 10;
        String tenantId = "concurrent-tenant";
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        registerTenantUseCase.register(new RegisterTenantCommand(
                                tenantId,
                                getTenantAUrl(),
                                "tenant_a",
                                "tenant_a"
                        ));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);

        // then — 정확히 1번만 성공
        assertThat(successCount.get())
                .as("등록 성공 횟수는 정확히 1이어야 함")
                .isEqualTo(1);
        assertThat(failCount.get())
                .as("나머지는 중복 등록 실패")
                .isEqualTo(threadCount - 1);
    }

    // ── clear() 동작 검증 ──────────────────────────────────────

    @Test
    @DisplayName("clear() 호출 후 컨텍스트가 제거된다")
    void contextHolder_clear_removesContext() {
        // given
        contextHolder.setTenant(new TenantId("tenant-clear"));
        assertThat(contextHolder.hasTenant()).isTrue();

        // when
        contextHolder.clear();

        // then
        assertThat(contextHolder.hasTenant()).isFalse();
        assertThat(contextHolder.getTenant()).isNull();
    }
}
