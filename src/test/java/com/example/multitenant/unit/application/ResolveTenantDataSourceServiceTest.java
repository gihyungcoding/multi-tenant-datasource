package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.command.ResolveTenantCommand;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.application.service.ResolveTenantDataSourceService;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.util.List;

/**
 * {@link ResolveTenantDataSourceService} 단위 테스트.
 * Spring 컨텍스트 없음. Port 와 ContextHolder 는 Mock 으로 교체.
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveTenantDataSourceService")
class ResolveTenantDataSourceServiceTest {

    @Mock private TenantPersistencePort persistencePort;
    @Mock private TenantContextHolder   contextHolder;
    @InjectMocks private ResolveTenantDataSourceService service;

    private static Tenant activeTenant(String id) {
        return Tenant.create(
                new TenantId(id),
                new DataSourceSpec("jdbc:postgresql://localhost/db", "user", "pass"),
                List.of()
        );
    }

    // ── ACTIVE 테넌트 ──────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 테넌트 resolve 시 contextHolder.setTenant() 를 올바른 TenantId 로 호출한다")
    void resolve_activeTenant_setsContextWithCorrectId() {
        TenantId id = new TenantId("tenant-a");
        when(persistencePort.findById(id)).thenReturn(Optional.of(activeTenant("tenant-a")));

        service.resolve(new ResolveTenantCommand("tenant-a"));

        verify(contextHolder).setTenant(eq(id));
    }

    // ── 존재하지 않는 테넌트 ──────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 tenantId resolve 시 TenantNotFoundException 발생")
    void resolve_notFound_throwsException() {
        when(persistencePort.findById(new TenantId("ghost")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(new ResolveTenantCommand("ghost")))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    @DisplayName("존재하지 않는 테넌트 resolve 시 contextHolder.setTenant() 는 호출되지 않는다")
    void resolve_notFound_doesNotSetContext() {
        when(persistencePort.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(new ResolveTenantCommand("ghost")))
                .isInstanceOf(TenantNotFoundException.class);

        verify(contextHolder, never()).setTenant(any());
    }

    // ── SUSPENDED 테넌트 ──────────────────────────────────────

    @Test
    @DisplayName("SUSPENDED 테넌트 resolve 시 TenantSuspendedException 발생")
    void resolve_suspendedTenant_throwsException() {
        Tenant suspended = activeTenant("tenant-s");
        suspended.suspend("정책 위반");
        when(persistencePort.findById(new TenantId("tenant-s")))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.resolve(new ResolveTenantCommand("tenant-s")))
                .isInstanceOf(TenantSuspendedException.class);
    }

    @Test
    @DisplayName("SUSPENDED 테넌트 resolve 시 contextHolder.setTenant() 는 호출되지 않는다")
    void resolve_suspendedTenant_doesNotSetContext() {
        Tenant suspended = activeTenant("tenant-s");
        suspended.suspend("정책 위반");
        when(persistencePort.findById(new TenantId("tenant-s")))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.resolve(new ResolveTenantCommand("tenant-s")))
                .isInstanceOf(TenantSuspendedException.class);

        verify(contextHolder, never()).setTenant(any());
    }
}
