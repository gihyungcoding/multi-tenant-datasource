package com.example.multitenant.interfaces.interceptor;

import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.domain.context.TenantContextHolder;
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
 *     3. MDC.put("tenantId")           — 이 요청의 모든 로그에 tenantId 자동 포함
 *     4. Timer.start()                 — 요청 처리 시간 측정 시작
 *
 *   afterCompletion()
 *     5. Timer.Sample.stop()           — 지표 기록 (tenant.http.requests, http.status 태그)
 *     6. MDC.remove("tenantId")        — 로그 컨텍스트 정리
 *     7. TenantContextHolder.clear()   — ThreadLocal 정리 (메모리 누수 방지)
 * </pre>
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
    public static final String METRIC_REQUESTS = "tenant.http.requests";

    private final ResolveTenantDataSourceUseCase resolveUseCase;
    private final TenantContextHolder            contextHolder;
    private final MeterRegistry                  meterRegistry;

    public TenantInterceptor(ResolveTenantDataSourceUseCase resolveUseCase,
                             TenantContextHolder contextHolder,
                             @Nullable MeterRegistry meterRegistry) {
        this.resolveUseCase = resolveUseCase;
        this.contextHolder  = contextHolder;
        // MeterRegistry 빈이 없는 슬라이스 테스트에서도 NPE 없이 동작
        this.meterRegistry  = (meterRegistry != null) ? meterRegistry : new SimpleMeterRegistry();
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
        resolveUseCase.resolve(new ResolveTenantCommand(tenantId));

        // 2. MDC — 컨트롤러·서비스·레포지토리의 모든 로그에 tenantId 자동 포함
        MDC.put(MDC_TENANT_KEY, tenantId);

        // 3. Timer 시작 — afterCompletion() 에서 stop()
        request.setAttribute(ATTR_TIMER, Timer.start(meterRegistry));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                @Nullable Exception ex) {
        try {
            // 4. Micrometer Timer 기록
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
            // 5. MDC 정리 — 스레드 풀 재사용 시 다음 요청의 로그에 누출 방지
            MDC.remove(MDC_TENANT_KEY);
            // 6. TenantContext 정리
            contextHolder.clear();
        }
    }
}
