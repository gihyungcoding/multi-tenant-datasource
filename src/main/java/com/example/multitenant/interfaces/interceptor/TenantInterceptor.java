package com.example.multitenant.interfaces.interceptor;

import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TenantId 추출·검증 인터셉터.
 *
 * <h2>단일 요청 처리 흐름</h2>
 * <pre>
 *   preHandle()
 *     1. X-Tenant-Id 헤더 유효성 검사
 *     2. ResolveTenantDataSourceUseCase — 테넌트 활성 여부 검증 + TenantContextHolder 세팅
 *     3. TenantRequestTracker.increment() — in-flight 요청 카운터 증가 (드레인 대기용)
 *     4. MDC.put("tenantId")             — 이 요청의 모든 로그에 tenantId 자동 포함
 *     5. Timer.start()                   — 요청 처리 시간 측정 시작
 *
 *   afterCompletion()
 *     6. Timer.Sample.stop()             — 지표 기록 (tenant.http.requests, http.status 태그)
 *     7. TenantRequestTracker.decrement() — in-flight 요청 카운터 감소
 *     8. MDC.remove("tenantId")          — 로그 컨텍스트 정리
 *     9. TenantContextHolder.clear()     — ThreadLocal 정리 (메모리 누수 방지)
 * </pre>
 *
 * <h2>요청 추적 (TenantRequestTracker)</h2>
 * increment 는 {@code resolveUseCase.resolve()} 성공 후에만 호출된다.
 * 헤더 누락·테넌트 미존재·정지된 테넌트 등으로 preHandle 이 false 를 반환하거나
 * 예외를 던지는 경우에는 increment 가 호출되지 않는다.
 * afterCompletion 은 {@link #ATTR_TRACKED} 요청 속성으로 increment 여부를 확인한 후
 * decrement 를 호출하므로 카운터 불일치가 발생하지 않는다.
 *
 * <h2>MeterRegistry 주입 전략</h2>
 * {@code @Nullable} 로 선언하여 Spring 컨텍스트에 {@link MeterRegistry} 빈이 없는 경우
 * (예: 특정 테스트 슬라이스) {@link SimpleMeterRegistry} 로 자동 대체한다.
 * 운영 환경에서는 Actuator 가 제공하는 {@code CompositeMeterRegistry} 가 주입된다.
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER   = "X-Tenant-Id";
    public static final String MDC_TENANT_KEY  = "tenantId";
    public static final String ATTR_TIMER      = "tenant.timer.sample";
    public static final String ATTR_TRACKED    = "tenant.request.tracked";   // increment 여부 플래그
    public static final String METRIC_REQUESTS = "tenant.http.requests";

    private final ResolveTenantDataSourceUseCase resolveUseCase;
    private final TenantContextHolder            contextHolder;
    private final TenantRequestTracker           requestTracker;
    private final MeterRegistry                  meterRegistry;

    public TenantInterceptor(ResolveTenantDataSourceUseCase resolveUseCase,
                             TenantContextHolder contextHolder,
                             TenantRequestTracker requestTracker,
                             @Nullable MeterRegistry meterRegistry) {
        this.resolveUseCase  = resolveUseCase;
        this.contextHolder   = contextHolder;
        this.requestTracker  = requestTracker;
        // MeterRegistry 빈이 없는 슬라이스 테스트에서도 NPE 없이 동작
        this.meterRegistry   = (meterRegistry != null) ? meterRegistry : new SimpleMeterRegistry();
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "X-Tenant-Id 헤더가 필요합니다.");
            return false;
        }

        // 1. 도메인 규칙 검증 + TenantContextHolder.setTenant()
        //    실패(미존재·정지) 시 예외 → increment 호출 안 됨
        resolveUseCase.resolve(new ResolveTenantCommand(tenantId));

        // 2. in-flight 카운터 증가 — 테넌트 드레인 시 이 요청의 완료를 기다린다
        requestTracker.increment(new TenantId(tenantId));
        request.setAttribute(ATTR_TRACKED, Boolean.TRUE); // afterCompletion 에서 decrement 여부 판별

        // 3. MDC — 컨트롤러·서비스·레포지토리의 모든 로그에 tenantId 자동 포함
        MDC.put(MDC_TENANT_KEY, tenantId);

        // 4. Timer 시작 — afterCompletion() 에서 stop()
        request.setAttribute(ATTR_TIMER, Timer.start(meterRegistry));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                @Nullable Exception ex) {
        try {
            // 5. Micrometer Timer 기록
            //    tag: tenant_id  — 테넌트별 분리
            //    tag: http.status — 상태 코드별 분리 (200, 4xx, 5xx)
            Timer.Sample sample   = (Timer.Sample) request.getAttribute(ATTR_TIMER);
            String       tenantId = request.getHeader(TENANT_HEADER);
            if (sample != null && tenantId != null) {
                sample.stop(Timer.builder(METRIC_REQUESTS)
                        .description("테넌트별 HTTP 요청 처리 시간")
                        .tag("tenant_id", tenantId)
                        .tag("http.status", String.valueOf(response.getStatus()))
                        .register(meterRegistry));
            }
        } finally {
            // 6. in-flight 카운터 감소 — preHandle 에서 증가한 경우에만 실행
            if (Boolean.TRUE.equals(request.getAttribute(ATTR_TRACKED))) {
                String tenantId = request.getHeader(TENANT_HEADER);
                if (tenantId != null) {
                    requestTracker.decrement(new TenantId(tenantId));
                }
            }
            // 7. MDC 정리 — 스레드 풀 재사용 시 다음 요청의 로그에 누출 방지
            MDC.remove(MDC_TENANT_KEY);
            // 8. TenantContext 정리
            contextHolder.clear();
        }
    }
}
