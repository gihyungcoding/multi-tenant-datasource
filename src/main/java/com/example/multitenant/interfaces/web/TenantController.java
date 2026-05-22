package com.example.multitenant.interfaces.web;

import com.example.multitenant.application.port.in.command.GetTenantCommand;
import com.example.multitenant.application.port.in.GetTenantUseCase;
import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.interfaces.web.dto.RegisterTenantRequest;
import com.example.multitenant.interfaces.web.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 멀티 테넌트 관리 endpoint
 * @author gihyung.lee
 * @since 2026-05-21
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {
    private final RegisterTenantUseCase registerUseCase;
    private final GetTenantUseCase getUseCase;

    public TenantController(RegisterTenantUseCase registerUseCase,
                            GetTenantUseCase getUseCase) {
        this.registerUseCase = registerUseCase;
        this.getUseCase = getUseCase;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> register(
            @Valid @RequestBody RegisterTenantRequest request) {
        RegisterTenantCommand command = new RegisterTenantCommand(
                request.tenantId(),
                request.url(),
                request.username(),
                request.password()
        );

        RegisterTenantResult result = registerUseCase.register(command);

        return ResponseEntity
                .created(URI.create("/api/tenants/" + result.tenantId()))
                .body(TenantResponse.from(result));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<TenantResponse> get(@PathVariable String tenantId) {
        GetTenantCommand command = new GetTenantCommand(tenantId);
        GetTenantResult result = getUseCase.getById(command);
        return ResponseEntity.ok(TenantResponse.from(result));
    }
}
