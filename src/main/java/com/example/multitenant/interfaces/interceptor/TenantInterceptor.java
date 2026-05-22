package com.example.multitenant.interfaces.interceptor;

import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.domain.context.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TenantId 추출, 검증 인터셉터
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final ResolveTenantDataSourceUseCase resolveUseCase;
    private final TenantContextHolder contextHolder;

    public TenantInterceptor(ResolveTenantDataSourceUseCase resolveUseCase,
                             TenantContextHolder contextHolder) {
        this.resolveUseCase = resolveUseCase;
        this.contextHolder = contextHolder;
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

        // 유스케이스 호출 — 도메인 규칙 검증 + 컨텍스트 세팅
        resolveUseCase.resolve(new ResolveTenantCommand(tenantId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        contextHolder.clear();
    }
}
