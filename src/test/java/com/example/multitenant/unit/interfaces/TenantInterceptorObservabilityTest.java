package com.example.multitenant.unit.interfaces;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import com.example.multitenant.interfaces.interceptor.TenantInterceptor;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.example.multitenant.interfaces.interceptor.TenantInterceptor.ATTR_TIMER;
import static com.example.multitenant.interfaces.interceptor.TenantInterceptor.MDC_TENANT_KEY;
import static com.example.multitenant.interfaces.interceptor.TenantInterceptor.METRIC_REQUESTS;
import static com.example.multitenant.interfaces.interceptor.TenantInterceptor.TENANT_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link TenantInterceptor} MDC·Micrometer 동작 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 직접 인스턴스를 생성하므로 I/O 없고 실행 속도가 빠르다.
 * {@link SimpleMeterRegistry} 로 Micrometer 를 경량화하여 사용한다.
 */
@SmallTest
@DisplayName("TenantInterceptor - MDC / Micrometer 동작")
class TenantInterceptorObservabilityTest {

    private SimpleMeterRegistry            meterRegistry;
    private ResolveTenantDataSourceUseCase resolveUseCase;
    private TenantContextHolder            contextHolder;
    private TenantRequestTracker           requestTracker;
    private TenantInterceptor              interceptor;

    private MockHttpServletRequest  request;
    private MockHttpServletResponse response;

    private static final String TENANT = "tenant-a";

    @BeforeEach
    void setUp() {
        meterRegistry  = new SimpleMeterRegistry();
        resolveUseCase = mock(ResolveTenantDataSourceUseCase.class);
        contextHolder  = mock(TenantContextHolder.class);
        requestTracker = mock(TenantRequestTracker.class);
        interceptor    = new TenantInterceptor(resolveUseCase, contextHolder, requestTracker, meterRegistry);

        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        doNothing().when(resolveUseCase).resolve(any(ResolveTenantCommand.class));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ── MDC ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDC — tenantId 컨텍스트")
    class MdcContext {

        @Test
        @DisplayName("preHandle() 성공 시 MDC 에 tenantId 가 설정된다")
        void preHandle_validTenant_setsMdc() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);

            interceptor.preHandle(request, response, new Object());

            assertThat(MDC.get(MDC_TENANT_KEY))
                    .as("MDC[tenantId] 가 헤더에서 읽은 값으로 설정되어야 한다")
                    .isEqualTo(TENANT);
        }

        @Test
        @DisplayName("afterCompletion() 호출 후 MDC 에서 tenantId 가 제거된다")
        void afterCompletion_clearsMdc() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);
            interceptor.preHandle(request, response, new Object());
            assertThat(MDC.get(MDC_TENANT_KEY)).isEqualTo(TENANT); // 사전 조건

            interceptor.afterCompletion(request, response, new Object(), null);

            assertThat(MDC.get(MDC_TENANT_KEY))
                    .as("afterCompletion() 이후 MDC[tenantId] 는 null 이어야 한다")
                    .isNull();
        }

        @Test
        @DisplayName("preHandle() 에서 헤더가 없으면 MDC 에 tenantId 가 설정되지 않는다")
        void preHandle_missingHeader_doesNotSetMdc() throws Exception {
            interceptor.preHandle(request, response, new Object()); // 헤더 없음

            assertThat(MDC.get(MDC_TENANT_KEY))
                    .as("헤더 누락 시 MDC 에 tenantId 가 설정되면 안 된다")
                    .isNull();
        }

        @Test
        @DisplayName("afterCompletion() 은 MDC 정리 후 TenantContextHolder.clear() 를 호출한다")
        void afterCompletion_clearsTenantContext() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);
            interceptor.preHandle(request, response, new Object());

            interceptor.afterCompletion(request, response, new Object(), null);

            verify(contextHolder).clear();
            assertThat(MDC.get(MDC_TENANT_KEY)).isNull();
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Micrometer — tenant.http.requests Timer")
    class TimerMetrics {

        @Test
        @DisplayName("preHandle() 성공 시 요청 속성에 Timer.Sample 이 저장된다")
        void preHandle_setsTimerSampleInRequest() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);

            interceptor.preHandle(request, response, new Object());

            assertThat(request.getAttribute(ATTR_TIMER))
                    .as("요청 속성에 Timer.Sample 이 저장되어야 한다")
                    .isInstanceOf(Timer.Sample.class);
        }

        @Test
        @DisplayName("afterCompletion() 후 tenant.http.requests 타이머가 1회 기록된다")
        void afterCompletion_recordsTimerOnce() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);
            response.setStatus(200);

            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);

            Timer timer = meterRegistry.find(METRIC_REQUESTS)
                    .tag("tenant_id", TENANT)
                    .timer();

            assertThat(timer)
                    .as("tenant.http.requests 타이머가 등록되어야 한다")
                    .isNotNull();
            assertThat(timer.count())
                    .as("타이머 호출 횟수는 1이어야 한다")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("afterCompletion() 후 타이머에 tenant_id 와 http.status 태그가 붙는다")
        void afterCompletion_timerHasCorrectTags() throws Exception {
            request.addHeader(TENANT_HEADER, TENANT);
            response.setStatus(200);

            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);

            Timer timer = meterRegistry.find(METRIC_REQUESTS)
                    .tag("tenant_id", TENANT)
                    .tag("http.status", "200")
                    .timer();

            assertThat(timer)
                    .as("tenant_id='%s', http.status='200' 태그가 있는 타이머가 존재해야 한다", TENANT)
                    .isNotNull();
        }

        @Test
        @DisplayName("여러 테넌트 요청은 tenant_id 태그로 분리된다")
        void afterCompletion_differentTenants_separatedByTag() throws Exception {
            String[] tenants = {"tenant-a", "tenant-b", "tenant-a"};
            for (String tenantId : tenants) {
                MockHttpServletRequest  req  = new MockHttpServletRequest();
                MockHttpServletResponse resp = new MockHttpServletResponse();
                req.addHeader(TENANT_HEADER, tenantId);
                resp.setStatus(200);

                interceptor.preHandle(req, resp, new Object());
                interceptor.afterCompletion(req, resp, new Object(), null);
            }

            Timer timerA = meterRegistry.find(METRIC_REQUESTS).tag("tenant_id", "tenant-a").timer();
            Timer timerB = meterRegistry.find(METRIC_REQUESTS).tag("tenant_id", "tenant-b").timer();

            assertThat(timerA).isNotNull();
            assertThat(timerB).isNotNull();
            assertThat(timerA.count()).as("tenant-a 요청 횟수").isEqualTo(2L);
            assertThat(timerB.count()).as("tenant-b 요청 횟수").isEqualTo(1L);
        }

        @Test
        @DisplayName("preHandle() 없이 afterCompletion() 이 호출되어도 예외가 발생하지 않는다")
        void afterCompletion_withoutPreHandle_noException() {
            // preHandle 실패 시(예: 헤더 누락) Timer.Sample 이 없는 상태에서 호출될 수 있음
            org.assertj.core.api.Assertions.assertThatCode(
                    () -> interceptor.afterCompletion(request, response, new Object(), null)
            ).doesNotThrowAnyException();
        }
    }
}
