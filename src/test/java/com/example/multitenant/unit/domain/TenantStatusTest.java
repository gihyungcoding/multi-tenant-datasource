package com.example.multitenant.unit.domain;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.InvalidTenantStatusException;
import com.example.multitenant.domain.tenant.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link TenantStatus} Sealed Interface 단위 테스트.
 */
@SmallTest
@DisplayName("TenantStatus Sealed Interface")
class TenantStatusTest {

    @Test
    @DisplayName("Active.code() 는 'ACTIVE' 를 반환한다")
    void active_code_returnsActiveCode() {
        assertThat(new TenantStatus.Active().code()).isEqualTo(TenantStatus.ACTIVE_CODE);
    }

    @Test
    @DisplayName("Suspended.code() 는 'SUSPENDED' 를 반환한다")
    void suspended_code_returnsSuspendedCode() {
        assertThat(new TenantStatus.Suspended("연체").code()).isEqualTo(TenantStatus.SUSPENDED_CODE);
    }

    @Test
    @DisplayName("Suspended 는 reason 을 보존한다")
    void suspended_preservesReason() {
        String reason = "서비스 위반";
        assertThat(new TenantStatus.Suspended(reason).reason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("from('ACTIVE', null) 은 Active 를 반환한다")
    void from_activeCode_returnsActive() {
        TenantStatus status = TenantStatus.from("ACTIVE", null);
        assertThat(status).isInstanceOf(TenantStatus.Active.class);
    }

    @Test
    @DisplayName("from('SUSPENDED', reason) 은 Suspended 를 반환한다")
    void from_suspendedCode_returnsSuspended() {
        TenantStatus status = TenantStatus.from("SUSPENDED", "연체");
        assertThat(status).isInstanceOf(TenantStatus.Suspended.class);
        assertThat(((TenantStatus.Suspended) status).reason()).isEqualTo("연체");
    }

    @Test
    @DisplayName("알 수 없는 코드는 InvalidTenantStatusException 발생")
    void from_unknownCode_throwsException() {
        assertThatThrownBy(() -> TenantStatus.from("UNKNOWN", null))
                .isInstanceOf(InvalidTenantStatusException.class);
    }
}
