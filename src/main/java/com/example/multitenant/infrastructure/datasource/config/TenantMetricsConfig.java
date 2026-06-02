package com.example.multitenant.infrastructure.datasource.config;

import com.example.multitenant.infrastructure.datasource.TenantDataSourceRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테넌트 DataSource 관련 Micrometer 지표 등록.
 *
 * <h2>등록 지표</h2>
 * <table>
 *   <tr><th>이름</th><th>종류</th><th>설명</th></tr>
 *   <tr>
 *     <td>{@code tenant.datasource.active}</td>
 *     <td>Gauge</td>
 *     <td>현재 라우팅 가능한 테넌트 DataSource 수.
 *         테넌트 등록 시 즉시 반영된다.</td>
 *   </tr>
 * </table>
 *
 * <h2>HTTP 요청 지표</h2>
 * HTTP 요청 처리 시간({@code tenant.http.requests})은
 * {@link com.example.multitenant.interfaces.interceptor.TenantInterceptor} 에서
 * 테넌트·HTTP 상태 코드 태그와 함께 기록된다.
 *
 * <h2>조회 방법</h2>
 * <pre>
 *   GET /actuator/metrics/tenant.datasource.active
 *   GET /actuator/metrics/tenant.http.requests?tag=tenant_id:tenant-a
 * </pre>
 *
 * @author gihyung.lee
 * @since 2026-05-29
 */
@Configuration
public class TenantMetricsConfig {

    /**
     * 활성 테넌트 DataSource 수를 추적하는 Gauge 를 MeterRegistry 에 바인딩한다.
     *
     * <p>{@link MeterBinder} 를 사용하면 {@link MeterRegistry} 빈이 완전히 초기화된
     * 후에 바인딩이 실행되므로, 빈 생성 순서에 의존하지 않아도 된다.
     *
     * <p>Gauge 는 {@link TenantDataSourceRegistry#size()} 를 호출하여 현재 값을
     * 실시간으로 읽으므로, 테넌트 등록/해제가 발생하면 자동으로 반영된다.
     */
    @Bean
    public MeterBinder tenantDatasourceGauge(TenantDataSourceRegistry registry) {
        return (MeterRegistry meterRegistry) ->
                Gauge.builder("tenant.datasource.active", registry, TenantDataSourceRegistry::size)
                        .description("현재 라우팅 가능한 테넌트 DataSource 수")
                        .register(meterRegistry);
    }
}
