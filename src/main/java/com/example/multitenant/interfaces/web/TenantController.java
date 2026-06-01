package com.example.multitenant.interfaces.web;

import com.example.multitenant.application.port.in.ActivateTenantUseCase;
import com.example.multitenant.application.port.in.GetTenantUseCase;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.SuspendTenantUseCase;
import com.example.multitenant.application.port.in.command.ActivateTenantCommand;
import com.example.multitenant.application.port.in.command.GetTenantCommand;
import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.command.SuspendTenantCommand;
import com.example.multitenant.application.port.in.results.ActivateTenantResult;
import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.application.port.in.results.SuspendTenantResult;
import com.example.multitenant.interfaces.web.dto.ApiResponse;
import com.example.multitenant.interfaces.web.dto.RegisterTenantRequest;
import com.example.multitenant.interfaces.web.dto.SuspendTenantRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 멀티 테넌트 관리 endpoint
 *
 * <h2>생명주기 API</h2>
 * <pre>
 *   POST   /api/tenants             — 테넌트 등록 (ACTIVE)
 *   GET    /api/tenants/{tenantId}  — 테넌트 조회
 *   PATCH  /api/tenants/{tenantId}/suspend   — 정지 (ACTIVE → SUSPENDED)
 *   PATCH  /api/tenants/{tenantId}/activate  — 재활성화 (SUSPENDED → ACTIVE)
 * </pre>
 *
 * @author gihyung.lee
 * @since 2026-05-21
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final RegisterTenantUseCase registerUseCase;
    private final GetTenantUseCase      getUseCase;
    private final SuspendTenantUseCase  suspendUseCase;
    private final ActivateTenantUseCase activateUseCase;

    public TenantController(RegisterTenantUseCase registerUseCase,
                            GetTenantUseCase      getUseCase,
                            SuspendTenantUseCase  suspendUseCase,
                            ActivateTenantUseCase activateUseCase) {
        this.registerUseCase = registerUseCase;
        this.getUseCase      = getUseCase;
        this.suspendUseCase  = suspendUseCase;
        this.activateUseCase = activateUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RegisterTenantResult>> register(
            @Valid @RequestBody RegisterTenantRequest request) {
        RegisterTenantResult result = registerUseCase.register(
                new RegisterTenantCommand(request.tenantId(), request.url(),
                        request.username(), request.password()));

        return ResponseEntity
                .created(URI.create("/api/tenants/" + result.tenantId()))
                .body(ApiResponse.ok(result));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<GetTenantResult>> get(@PathVariable String tenantId) {
        GetTenantResult result = getUseCase.getById(new GetTenantCommand(tenantId));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 테넌트를 정지한다.
     *
     * <p>정지 즉시 라우팅 테이블에서 제거되어 이후 요청은 403 으로 거부된다.
     * 이미 정지된 테넌트에 호출하면 409 Conflict 를 반환한다.
     */
    @PatchMapping("/{tenantId}/suspend")
    public ResponseEntity<ApiResponse<SuspendTenantResult>> suspend(
            @PathVariable String tenantId,
            @Valid @RequestBody SuspendTenantRequest request) {
        SuspendTenantResult result = suspendUseCase.suspend(
                new SuspendTenantCommand(tenantId, request.reason()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 정지된 테넌트를 재활성화한다.
     *
     * <p>재활성화 즉시 새 HikariCP 풀이 생성되고 라우팅 테이블에 추가된다.
     * 이미 활성화된 테넌트에 호출하면 409 Conflict 를 반환한다.
     */
    @PatchMapping("/{tenantId}/activate")
    public ResponseEntity<ApiResponse<ActivateTenantResult>> activate(@PathVariable String tenantId) {
        ActivateTenantResult result = activateUseCase.activate(new ActivateTenantCommand(tenantId));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
