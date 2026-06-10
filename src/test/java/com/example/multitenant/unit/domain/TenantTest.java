package com.example.multitenant.unit.domain;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link Tenant} 도메인 애그리거트 루트 단위 테스트.
 * I/O 없음. Spring 컨텍스트 없음.
 */
@SmallTest
@DisplayName("Tenant 도메인")
class TenantTest {

    private TenantId tenantId;
    private DataSourceSpec spec;

    @BeforeEach
    void setUp() {
        tenantId = new TenantId("tenant-a");
        spec     = new DataSourceSpec("jdbc:postgresql://localhost/db", "user", "pass");
    }

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("생성 시 ACTIVE 상태이다")
        void create_isActive() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            assertThat(tenant.isActive()).isTrue();
            assertThat(tenant.getStatus()).isInstanceOf(TenantStatus.Active.class);
        }

        @Test
        @DisplayName("id 와 DataSourceSpec 이 올바르게 설정된다")
        void create_setsIdAndSpec() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            assertThat(tenant.getId()).isEqualTo(tenantId);
            assertThat(tenant.getDataSourceSpec()).isEqualTo(spec);
        }

        @Test
        @DisplayName("생성 시각이 null 이 아니다")
        void create_createdAtIsNotNull() {
            assertThat(Tenant.create(tenantId, spec, List.of()).getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("suspend()")
    class Suspend {

        @Test
        @DisplayName("정지 후 SUSPENDED 상태이다")
        void suspend_changeStatusToSuspended() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            tenant.suspend("연체");
            assertThat(tenant.getStatus()).isInstanceOf(TenantStatus.Suspended.class);
            assertThat(((TenantStatus.Suspended) tenant.getStatus()).reason()).isEqualTo("연체");
        }

        @Test
        @DisplayName("이미 정지된 테넌트를 재정지하면 TenantAlreadySuspendedException 발생")
        void suspend_alreadySuspended_throwsException() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            tenant.suspend("1차 연체");

            assertThatThrownBy(() -> tenant.suspend("2차 연체"))
                    .isInstanceOf(TenantAlreadySuspendedException.class);
        }
    }

    @Nested
    @DisplayName("activate()")
    class Activate {

        @Test
        @DisplayName("정지 상태에서 활성화하면 ACTIVE 상태이다")
        void activate_fromSuspended_isActive() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            tenant.suspend("연체");
            tenant.activate();
            assertThat(tenant.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("validateRoutable()")
    class ValidateRoutable {

        @Test
        @DisplayName("ACTIVE 테넌트는 예외 없이 통과한다")
        void validateRoutable_activeTenant_noException() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            assertThatCode(tenant::validateRoutable).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SUSPENDED 테넌트는 TenantSuspendedException 발생")
        void validateRoutable_suspendedTenant_throwsException() {
            Tenant tenant = Tenant.create(tenantId, spec, List.of());
            tenant.suspend("서비스 위반");

            assertThatThrownBy(tenant::validateRoutable)
                    .isInstanceOf(TenantSuspendedException.class);
        }
    }
}
