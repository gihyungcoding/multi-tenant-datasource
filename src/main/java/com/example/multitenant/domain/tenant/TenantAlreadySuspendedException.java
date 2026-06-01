package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.exception.DomainException;
import com.example.multitenant.domain.exception.ErrorCode;

import java.util.Map;

/**
 * 이미 정지된 테넌트를 다시 정지하려 할 때 발생하는 예외.
 *
 * <p>{@link TenantSuspendedException}(403 Forbidden)과 구분된다.
 * 이 예외는 상태 전환 충돌(409 Conflict)을 나타내며,
 * {@link Tenant#suspend}가 이미 정지 상태인 테넌트에 호출될 때 던진다.
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
public class TenantAlreadySuspendedException extends DomainException {

    private final String tenantId;

    public TenantAlreadySuspendedException(TenantId tenantId) {
        super(ErrorCode.TENANT_ALREADY_SUSPENDED);
        this.tenantId = tenantId.value();
    }

    @Override
    public Map<String, Object> getDetails() {
        return Map.of("tenantId", tenantId);
    }
}
