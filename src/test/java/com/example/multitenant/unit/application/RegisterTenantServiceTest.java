package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.application.port.out.TenantDataSourcePort;
import com.example.multitenant.application.port.out.TenantPersistencePort;
import com.example.multitenant.application.service.RegisterTenantService;
import com.example.multitenant.domain.tenant.TenantAlreadyExistsException;
import com.example.multitenant.domain.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RegisterTenantService} 단위 테스트.
 * Spring 컨텍스트 없음. Port 는 Mock 으로 교체.
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("RegisterTenantService")
class RegisterTenantServiceTest {

    @Mock private TenantPersistencePort persistencePort;
    @Mock private TenantDataSourcePort  dataSourcePort;
    @InjectMocks private RegisterTenantService service;

    private static final RegisterTenantCommand VALID_COMMAND = new RegisterTenantCommand(
            "tenant-a",
            "jdbc:postgresql://localhost:5432/tenant_a",
            "user",
            "pass"
    );

    // ── 정상 등록 ──────────────────────────────────────────────

    @Test
    @DisplayName("신규 테넌트 등록 시 tenantId 와 ACTIVE 상태를 반환한다")
    void register_newTenant_returnsActiveResult() {
        when(persistencePort.existsById(new TenantId("tenant-a"))).thenReturn(false);

        RegisterTenantResult result = service.register(VALID_COMMAND);

        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("신규 테넌트 등록 시 persistencePort.save() 를 1회 호출한다")
    void register_newTenant_savesToPersistencePort() {
        when(persistencePort.existsById(any())).thenReturn(false);

        service.register(VALID_COMMAND);

        verify(persistencePort, times(1)).save(any());
    }

    @Test
    @DisplayName("신규 테넌트 등록 시 dataSourcePort.register() 를 1회 호출한다")
    void register_newTenant_registersDataSource() {
        when(persistencePort.existsById(any())).thenReturn(false);

        service.register(VALID_COMMAND);

        verify(dataSourcePort, times(1)).register(any(), any(), any());
    }

    // ── 중복 등록 ──────────────────────────────────────────────

    @Test
    @DisplayName("이미 존재하는 tenantId 로 등록 시 TenantAlreadyExistsException 발생")
    void register_duplicate_throwsException() {
        when(persistencePort.existsById(new TenantId("tenant-a"))).thenReturn(true);

        assertThatThrownBy(() -> service.register(VALID_COMMAND))
                .isInstanceOf(TenantAlreadyExistsException.class)
                .hasMessageContaining("tenant-a");
    }

    @Test
    @DisplayName("중복 등록 시 persistencePort.save() 는 호출되지 않는다")
    void register_duplicate_doesNotSave() {
        when(persistencePort.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> service.register(VALID_COMMAND))
                .isInstanceOf(TenantAlreadyExistsException.class);

        verify(persistencePort, never()).save(any());
    }

    @Test
    @DisplayName("중복 등록 시 dataSourcePort.register() 는 호출되지 않는다")
    void register_duplicate_doesNotRegisterDataSource() {
        when(persistencePort.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> service.register(VALID_COMMAND))
                .isInstanceOf(TenantAlreadyExistsException.class);

        verify(dataSourcePort, never()).register(any(), any(), any());
    }
}
