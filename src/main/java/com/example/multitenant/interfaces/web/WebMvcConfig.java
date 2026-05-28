package com.example.multitenant.interfaces.web;

import com.example.multitenant.interfaces.interceptor.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 설정 — TenantInterceptor 등록.
 *
 * <p>인터셉터 적용 범위:
 * <ul>
 *   <li>{@code /api/demo/**} — 테넌트 DataSource 라우팅이 필요한 경로</li>
 * </ul>
 * 테넌트 관리 API ({@code /api/tenants/**})는 masterDataSource를 직접 사용하므로
 * 인터셉터를 적용하지 않는다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/demo/**");
    }
}
