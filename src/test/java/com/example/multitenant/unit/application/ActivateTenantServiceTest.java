package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.command.ActivateTenantCommand;
import com.example.multitenant.application.port.in.results.ActivateTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.application.service.ActivateTenantService;
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
import static org.mockito.Mockito.*;
import java.util.List;

/**
 * {@link ActivateTenantService} 단위 테스트.
 * Spring 컨텍스트 없음. Port 는 Mock 으로 교체.
 * @author gihyung.lee
 * @since 2026-06-01
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivateTenantService")
class ActivateTenantServiceTest {

    @Mock private TenantPersistencePort persistencePort;
    @Mock private TenantDataSourcePort  dataSourcePort;
    @InjectMocks private ActivateTenantService service;

    private static final String   TENANT_ID = "tenant-a";
    private static final TenantId ID        = new TenantId(TENANT_ID);

    private Tenant suspendedTenant() {
        Tenant t = Tenant.create(ID, new DataSourceSpec(
                "jdbc:postgresql://localhost:5432/tenant_a", "user", "pass"), List.of());
        t.suspend("유지보수");
        return t;
    }

    private Tenant activeTenant() {
        return Tenant.create(ID, new DataSourceSpec(
                "jdbc:postgresql://localhost:5432/tenant_a", "user", "pass"), List.of());
    }

    // ── 정상 재활성화 ──────────────────────────────────────────

    @Test
    @DisplayName("SUSPENDED 테넌트 재활성화 시 ACTIVE 상태를 반환한다")
    void activate_suspendedTenant_returnsActiveResult() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(suspendedTenant()));

        ActivateTenantResult result = service.activate(new ActivateTenantCommand(TENANT_ID));

        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("재활성화 시 persistencePort.save() 를 1회 호출한다")
    void activate_callsSave() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(suspendedTenant()));

        service.activate(new ActivateTenantCommand(TENANT_ID));

        verify(persistencePort, times(1)).save(any());
    }

    @Test
    @DisplayName("재활성화 시 dataSourcePort.register() 를 1회 호출한다")
    void activate_callsRegister() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(suspendedTenant()));

        service.activate(new ActivateTenantCommand(TENANT_ID));

        verify(dataSourcePort, times(1)).register(any(), any(), any());
    }

    // ── 예외 시나리오 ──────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 tenantId 재활성화 시 TenantNotFoundException 발생")
    void activate_notFound_throwsException() {
        when(persistencePort.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(new ActivateTenantCommand(TENANT_ID)))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    @DisplayName("이미 활성화된 테넌트 재활성화 시 TenantAlreadyActiveException 발생")
    void activate_alreadyActive_throwsException() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(activeTenant()));

        assertThatThrownBy(() -> service.activate(new ActivateTenantCommand(TENANT_ID)))
                .isInstanceOf(TenantAlreadyActiveException.class);
    }

    @Test
    @DisplayName("재활성화 실패 시 dataSourcePort.register() 는 호출되지 않는다")
    void activate_alreadyActive_doesNotRegister() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(activeTenant()));

        assertThatThrownBy(() -> service.activate(new ActivateTenantCommand(TENANT_ID)))
                .isInstanceOf(TenantAlreadyActiveException.class);

        verify(dataSourcePort, never()).register(any(), any(), any());
    }
}
