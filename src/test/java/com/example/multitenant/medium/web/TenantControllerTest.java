package com.example.multitenant.medium.web;

import com.example.multitenant.annotation.MediumTest;
import com.example.multitenant.application.port.in.ActivateTenantUseCase;
import com.example.multitenant.application.port.in.GetTenantUseCase;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.application.port.in.SuspendTenantUseCase;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.application.port.in.results.ActivateTenantResult;
import com.example.multitenant.application.port.in.results.GetTenantResult;
import com.example.multitenant.application.port.in.results.RegisterTenantResult;
import com.example.multitenant.application.port.in.results.SuspendTenantResult;
import com.example.multitenant.domain.tenant.TenantAlreadyActiveException;
import com.example.multitenant.domain.tenant.TenantAlreadyExistsException;
import com.example.multitenant.domain.tenant.TenantAlreadySuspendedException;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.domain.tenant.TenantNotFoundException;
import com.example.multitenant.interfaces.web.TenantController;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link TenantController} Web 레이어 슬라이스 테스트.
 * Spring 컨텍스트: WebMvcTest (Controller + ControllerAdvice).
 * 실제 외부 서비스 없음. UseCase 는 Mock 으로 교체.
 */
@MediumTest
@WebMvcTest(TenantController.class)
@DisplayName("TenantController")
class TenantControllerTest {

    @Autowired MockMvc     mockMvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean RegisterTenantUseCase          registerUseCase;
    @MockitoBean GetTenantUseCase               getUseCase;
    @MockitoBean SuspendTenantUseCase           suspendUseCase;
    @MockitoBean ActivateTenantUseCase          activateUseCase;
    // TenantInterceptor (@Component + HandlerInterceptor) is loaded in WebMvcTest slice;
    // its dependencies must be mocked so the context starts.
    @MockitoBean ResolveTenantDataSourceUseCase resolveTenantDataSourceUseCase;
    @MockitoBean TenantContextHolder            tenantContextHolder;

    // ── POST /api/tenants ──────────────────────────────────────

    @Nested
    @DisplayName("POST /api/tenants")
    class Register {

        @Test
        @DisplayName("유효한 요청 시 201 Created 와 tenantId, ACTIVE status 를 반환한다")
        void register_validRequest_returns201() throws Exception {
            when(registerUseCase.register(any()))
                    .thenReturn(new RegisterTenantResult("tenant-a", "ACTIVE"));

            String body = mapper.writeValueAsString(Map.of(
                    "tenantId",  "tenant-a",
                    "url",       "jdbc:postgresql://localhost:5432/db",
                    "username",  "user",
                    "password",  "pass"
            ));

            mockMvc.perform(post("/api/tenants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("tenantId 가 blank 이면 400 Bad Request 를 반환한다")
        void register_blankTenantId_returns400() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "tenantId",  "  ",
                    "url",       "jdbc:postgresql://localhost:5432/db",
                    "username",  "user",
                    "password",  "pass"
            ));

            mockMvc.perform(post("/api/tenants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("url 이 blank 이면 400 Bad Request 를 반환한다")
        void register_blankUrl_returns400() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "tenantId",  "tenant-a",
                    "url",       "",
                    "username",  "user",
                    "password",  "pass"
            ));

            mockMvc.perform(post("/api/tenants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("이미 존재하는 테넌트 등록 시 409 Conflict 를 반환한다")
        void register_duplicate_returns409() throws Exception {
            when(registerUseCase.register(any()))
                    .thenThrow(new TenantAlreadyExistsException(new TenantId("tenant-a")));

            String body = mapper.writeValueAsString(Map.of(
                    "tenantId",  "tenant-a",
                    "url",       "jdbc:postgresql://localhost:5432/db",
                    "username",  "user",
                    "password",  "pass"
            ));

            mockMvc.perform(post("/api/tenants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ── GET /api/tenants/{tenantId} ────────────────────────────

    @Nested
    @DisplayName("GET /api/tenants/{tenantId}")
    class Get {

        @Test
        @DisplayName("존재하는 tenantId 조회 시 200 OK 와 ACTIVE status 를 반환한다")
        void get_existingTenant_returns200() throws Exception {
            when(getUseCase.getById(any()))
                    .thenReturn(new GetTenantResult("tenant-a", "ACTIVE", null));

            mockMvc.perform(get("/api/tenants/tenant-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("SUSPENDED 테넌트 조회 시 200 OK 와 suspendReason 이 포함된다")
        void get_suspendedTenant_includesReason() throws Exception {
            when(getUseCase.getById(any()))
                    .thenReturn(new GetTenantResult("tenant-b", "SUSPENDED", "연체"));

            mockMvc.perform(get("/api/tenants/tenant-b"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.data.suspendReason").value("연체"));
        }

        @Test
        @DisplayName("존재하지 않는 tenantId 조회 시 404 Not Found 를 반환한다")
        void get_notFound_returns404() throws Exception {
            when(getUseCase.getById(any()))
                    .thenThrow(new TenantNotFoundException(new TenantId("ghost")));

            mockMvc.perform(get("/api/tenants/ghost"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ── PATCH /api/tenants/{tenantId}/suspend ──────────────────

    @Nested
    @DisplayName("PATCH /api/tenants/{tenantId}/suspend")
    class Suspend {

        @Test
        @DisplayName("ACTIVE 테넌트 정지 시 200 OK 와 SUSPENDED status 를 반환한다")
        void suspend_activeTenant_returns200() throws Exception {
            when(suspendUseCase.suspend(any()))
                    .thenReturn(new SuspendTenantResult("tenant-a", "SUSPENDED"));

            String body = mapper.writeValueAsString(Map.of("reason", "유지보수"));

            mockMvc.perform(patch("/api/tenants/tenant-a/suspend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        }

        @Test
        @DisplayName("이미 정지된 테넌트 재정지 시 409 Conflict 를 반환한다")
        void suspend_alreadySuspended_returns409() throws Exception {
            when(suspendUseCase.suspend(any()))
                    .thenThrow(new TenantAlreadySuspendedException(new TenantId("tenant-a")));

            String body = mapper.writeValueAsString(Map.of("reason", "사유"));

            mockMvc.perform(patch("/api/tenants/tenant-a/suspend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("reason 이 blank 이면 400 Bad Request 를 반환한다")
        void suspend_blankReason_returns400() throws Exception {
            String body = mapper.writeValueAsString(Map.of("reason", ""));

            mockMvc.perform(patch("/api/tenants/tenant-a/suspend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ── PATCH /api/tenants/{tenantId}/activate ─────────────────

    @Nested
    @DisplayName("PATCH /api/tenants/{tenantId}/activate")
    class Activate {

        @Test
        @DisplayName("SUSPENDED 테넌트 재활성화 시 200 OK 와 ACTIVE status 를 반환한다")
        void activate_suspendedTenant_returns200() throws Exception {
            when(activateUseCase.activate(any()))
                    .thenReturn(new ActivateTenantResult("tenant-a", "ACTIVE"));

            mockMvc.perform(patch("/api/tenants/tenant-a/activate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("이미 활성화된 테넌트 재활성화 시 409 Conflict 를 반환한다")
        void activate_alreadyActive_returns409() throws Exception {
            when(activateUseCase.activate(any()))
                    .thenThrow(new TenantAlreadyActiveException(new TenantId("tenant-a")));

            mockMvc.perform(patch("/api/tenants/tenant-a/activate"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
