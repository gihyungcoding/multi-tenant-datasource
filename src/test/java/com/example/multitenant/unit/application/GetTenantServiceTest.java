package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.command.GetTenantCommand;
import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.application.service.GetTenantService;
import com.example.multitenant.domain.tenant.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

/**
 * {@link GetTenantService} 단위 테스트.
 * Spring 컨텍스트 없음. TenantPersistencePort 는 Mock 으로 교체.
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("GetTenantService")
class GetTenantServiceTest {

    @Mock private TenantPersistencePort persistencePort;
    @InjectMocks private GetTenantService service;

    private static Tenant activeTenant(String id) {
        return Tenant.create(
                new TenantId(id),
                new DataSourceSpec("jdbc:postgresql://localhost/db", "user", "pass"),
                List.of()
        );
    }

    // ── 정상 조회 ──────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 테넌트 조회 시 tenantId 와 ACTIVE status 를 반환한다")
    void getById_activeTenant_returnsActiveResult() {
        Tenant tenant = activeTenant("tenant-a");
        when(persistencePort.findById(new TenantId("tenant-a")))
                .thenReturn(Optional.of(tenant));

        GetTenantResult result = service.getById(new GetTenantCommand("tenant-a"));

        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.suspendReason()).isNull();
    }

    @Test
    @DisplayName("SUSPENDED 테넌트 조회 시 SUSPENDED status 와 reason 이 포함된다")
    void getById_suspendedTenant_returnsSuspendedWithReason() {
        Tenant tenant = activeTenant("tenant-b");
        tenant.suspend("연체");
        when(persistencePort.findById(new TenantId("tenant-b")))
                .thenReturn(Optional.of(tenant));

        GetTenantResult result = service.getById(new GetTenantCommand("tenant-b"));

        assertThat(result.status()).isEqualTo("SUSPENDED");
        assertThat(result.suspendReason()).isEqualTo("연체");
    }

    // ── 존재하지 않는 테넌트 ──────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 tenantId 조회 시 TenantNotFoundException 발생")
    void getById_notFound_throwsException() {
        when(persistencePort.findById(new TenantId("ghost")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(new GetTenantCommand("ghost")))
                .isInstanceOf(TenantNotFoundException.class);
    }
}
