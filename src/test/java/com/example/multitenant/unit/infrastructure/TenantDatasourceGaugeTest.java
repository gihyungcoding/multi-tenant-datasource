package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry;
import com.example.multitenant.infrastructure.datasource.config.TenantMetricsConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tenant.datasource.active} Gauge 단위 테스트.
 *
 * <p>실제 DataSource 연결 없이 {@link TenantDataSourceRegistry#size()} 가
 * Gauge 에 실시간 반영되는지 검증한다.
 *
 * <ul>
 *   <li>Gauge 등록 여부</li>
 *   <li>테넌트 등록/해제에 따른 값 변화</li>
 * </ul>
 */
@SmallTest
@DisplayName("tenant.datasource.active Gauge")
class TenantDatasourceGaugeTest {

    private SimpleMeterRegistry      meterRegistry;
    private TenantDataSourceRegistry registry;

    /**
     * 실제 HikariCP 풀을 생성하지 않고 내부 맵만 조작하기 위한 Stub 레지스트리.
     * {@code size()} 만 Gauge 에서 호출되므로, 내부 맵을 직접 제어해도 충분하다.
     */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registry      = new TenantDataSourceRegistry();

        // TenantMetricsConfig 와 동일한 방식으로 Gauge 바인딩
        new TenantMetricsConfig()
                .tenantDatasourceGauge(registry)
                .bindTo(meterRegistry);
    }

    // ── Gauge 등록 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("MeterRegistry 에 tenant.datasource.active Gauge 가 등록된다")
    void gauge_isRegistered() {
        Gauge gauge = meterRegistry.find("tenant.datasource.active").gauge();

        assertThat(gauge)
                .as("tenant.datasource.active Gauge 가 등록되어야 한다")
                .isNotNull();
    }

    // ── 초기값 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("초기 상태에서 Gauge 값은 0이다")
    void gauge_initialValue_isZero() {
        Gauge gauge = meterRegistry.find("tenant.datasource.active").gauge();

        assertThat(gauge.value())
                .as("등록된 DataSource 없을 때 Gauge 값은 0이어야 한다")
                .isEqualTo(0.0);
    }

    // ── 실시간 반영 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("테넌트 DataSource 등록 후 Gauge 값이 1 증가한다")
    void gauge_afterRegister_incrementsByOne() {
        injectDataSource("tenant-a");

        Gauge gauge = meterRegistry.find("tenant.datasource.active").gauge();

        assertThat(gauge.value())
                .as("DataSource 1개 등록 후 Gauge 값은 1이어야 한다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("테넌트 DataSource 해제 후 Gauge 값이 1 감소한다")
    void gauge_afterDeregister_decrementsByOne() {
        injectDataSource("tenant-a");
        injectDataSource("tenant-b");

        // tenant-a 만 제거
        registry.unregisterAndSnapshot(new TenantId("tenant-a"));

        Gauge gauge = meterRegistry.find("tenant.datasource.active").gauge();

        assertThat(gauge.value())
                .as("DataSource 1개 해제 후 Gauge 값은 1이어야 한다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("여러 테넌트 등록 시 Gauge 값이 정확히 일치한다")
    void gauge_multipleRegistrations_reflectsExactCount() {
        injectDataSource("tenant-a");
        injectDataSource("tenant-b");
        injectDataSource("tenant-c");

        Gauge gauge = meterRegistry.find("tenant.datasource.active").gauge();

        assertThat(gauge.value())
                .as("3개 등록 후 Gauge 값은 3이어야 한다")
                .isEqualTo(3.0);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * 실제 DB 연결 없이 내부 맵에 더미 DataSource 를 직접 삽입한다.
     * Gauge 는 {@code registry.size()} 만 읽으므로 DataSource 구현체는 무관하다.
     * 키는 복합 키 형식({@code "tenantId:master"})을 사용한다.
     */
    @SuppressWarnings("unchecked")
    private void injectDataSource(String tenantId) {
        Map<String, Object> map = (Map<String, Object>)
                ReflectionTestUtils.getField(registry, "dataSourceMap");
        map.put(tenantId + ":master", new StubDataSource());
    }

    /** HikariCP 연결 없이 size() 계산만 가능하게 하는 최소 stub */
    private static class StubDataSource implements javax.sql.DataSource {
        @Override public java.sql.Connection getConnection() { return null; }
        @Override public java.sql.Connection getConnection(String u, String p) { return null; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter pw) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
