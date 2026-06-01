package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.command.SuspendTenantCommand;
import com.example.multitenant.application.port.in.results.SuspendTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.application.service.SuspendTenantService;
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

/**
 * {@link SuspendTenantService} 단위 테스트.
 * Spring 컨텍스트 없음. Port 는 Mock 으로 교체.
 * @author gihyung.lee
 * @since 2026-06-01
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("SuspendTenantService")
class SuspendTenantServiceTest {

    @Mock private TenantPersistencePort persistencePort;
    @Mock private TenantDataSourcePort  dataSourcePort;
    @InjectMocks private SuspendTenantService service;

    private static final String   TENANT_ID = "tenant-a";
    private static final String   REASON    = "유지보수";
    private static final TenantId ID        = new TenantId(TENANT_ID);

    private Tenant activeTenant() {
        return Tenant.create(ID, new DataSourceSpec(
                "jdbc:postgresql://localhost:5432/tenant_a", "user", "pass"));
    }

    private Tenant suspendedTenant() {
        Tenant t = activeTenant();
        t.suspend(REASON);
        return t;
    }

    // ── 정상 정지 ──────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 테넌트 정지 시 SUSPENDED 상태를 반환한다")
    void suspend_activeTenant_returnsSuspendedResult() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(activeTenant()));

        SuspendTenantResult result = service.suspend(new SuspendTenantCommand(TENANT_ID, REASON));

        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.status()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("정지 시 persistencePort.save() 를 1회 호출한다")
    void suspend_callsSave() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(activeTenant()));

        service.suspend(new SuspendTenantCommand(TENANT_ID, REASON));

        verify(persistencePort, times(1)).save(any());
    }

    @Test
    @DisplayName("정지 시 dataSourcePort.deregister() 를 1회 호출한다")
    void suspend_callsDeregister() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(activeTenant()));

        service.suspend(new SuspendTenantCommand(TENANT_ID, REASON));

        verify(dataSourcePort, times(1)).deregister(ID);
    }

    // ── 예외 시나리오 ──────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 tenantId 정지 시 TenantNotFoundException 발생")
    void suspend_notFound_throwsException() {
        when(persistencePort.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(TENANT_ID, REASON)))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    @DisplayName("이미 정지된 테넌트 재정지 시 TenantAlreadySuspendedException 발생")
    void suspend_alreadySuspended_throwsException() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(suspendedTenant()));

        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(TENANT_ID, REASON)))
                .isInstanceOf(TenantAlreadySuspendedException.class);
    }

    @Test
    @DisplayName("정지 실패 시 dataSourcePort.deregister() 는 호출되지 않는다")
    void suspend_alreadySuspended_doesNotDeregister() {
        when(persistencePort.findById(ID)).thenReturn(Optional.of(suspendedTenant()));

        assertThatThrownBy(() -> service.suspend(new SuspendTenantCommand(TENANT_ID, REASON)))
                .isInstanceOf(TenantAlreadySuspendedException.class);

        verify(dataSourcePort, never()).deregister(any());
    }
}
