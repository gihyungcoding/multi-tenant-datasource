package com.example.multitenant.medium.web;

import com.example.multitenant.annotation.MediumTest;
import com.example.multitenant.application.port.in.ResolveTenantDataSourceUseCase;
import com.example.multitenant.demo.application.DemoMessageResult;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.infrastructure.datasource.TenantRequestTracker;
import com.example.multitenant.demo.application.port.in.GetDemoMessagesUseCase;
import com.example.multitenant.demo.application.port.in.SaveDemoMessageUseCase;
import com.example.multitenant.demo.interfaces.DemoController;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link DemoController} Web 레이어 슬라이스 테스트.
 * Spring 컨텍스트: WebMvcTest (Controller + ControllerAdvice).
 * 실제 외부 서비스 없음. UseCase 는 Mock 으로 교체.
 */
@MediumTest
@WebMvcTest(DemoController.class)
@DisplayName("DemoController")
class DemoControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean SaveDemoMessageUseCase        saveUseCase;
    @MockitoBean GetDemoMessagesUseCase        getUseCase;
    // TenantInterceptor (@Component + HandlerInterceptor) is loaded in WebMvcTest slice;
    // its dependencies must be mocked so the context starts.
    @MockitoBean ResolveTenantDataSourceUseCase resolveTenantDataSourceUseCase;
    @MockitoBean TenantContextHolder            tenantContextHolder;
    @MockitoBean TenantRequestTracker           tenantRequestTracker;

    private static final LocalDateTime NOW     = LocalDateTime.of(2026, 5, 28, 9, 0);
    private static final String        TENANT  = "tenant-a";

    // ── POST /api/demo ─────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/demo")
    class Save {

        @Test
        @DisplayName("유효한 content 로 요청 시 200 OK 와 저장된 메시지를 반환한다")
        void save_validContent_returns200() throws Exception {
            DemoMessageResult saved = new DemoMessageResult(1L, TENANT, "hello", NOW);
            when(saveUseCase.save(any())).thenReturn(saved);

            String body = mapper.writeValueAsString(Map.of("content", "hello"));

            mockMvc.perform(post("/api/demo")
                            .header("X-Tenant-Id", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.content").value("hello"))
                    .andExpect(jsonPath("$.data.tenantId").value(TENANT));
        }

        @Test
        @DisplayName("content 가 blank 이면 400 Bad Request 를 반환한다")
        void save_blankContent_returns400() throws Exception {
            String body = mapper.writeValueAsString(Map.of("content", "  "));

            mockMvc.perform(post("/api/demo")
                            .header("X-Tenant-Id", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("content 가 없으면 400 Bad Request 를 반환한다")
        void save_missingContent_returns400() throws Exception {
            mockMvc.perform(post("/api/demo")
                            .header("X-Tenant-Id", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("X-Tenant-Id 헤더가 없으면 인터셉터가 400 을 반환한다")
        void save_missingTenantHeader_returns400() throws Exception {
            mockMvc.perform(post("/api/demo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("content", "hello"))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── GET /api/demo ──────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/demo")
    class FindAll {

        @Test
        @DisplayName("메시지가 있으면 200 OK 와 전체 목록을 반환한다")
        void findAll_withMessages_returns200() throws Exception {
            List<DemoMessageResult> messages = List.of(
                    new DemoMessageResult(1L, TENANT, "msg-1", NOW),
                    new DemoMessageResult(2L, TENANT, "msg-2", NOW)
            );
            when(getUseCase.findAll()).thenReturn(messages);

            mockMvc.perform(get("/api/demo")
                            .header("X-Tenant-Id", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].content").value("msg-1"))
                    .andExpect(jsonPath("$.data[1].content").value("msg-2"));
        }

        @Test
        @DisplayName("메시지가 없으면 200 OK 와 빈 배열을 반환한다")
        void findAll_noMessages_returnsEmptyArray() throws Exception {
            when(getUseCase.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/demo")
                            .header("X-Tenant-Id", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("X-Tenant-Id 헤더가 없으면 인터셉터가 400 을 반환한다")
        void findAll_missingTenantHeader_returns400() throws Exception {
            mockMvc.perform(get("/api/demo"))
                    .andExpect(status().isBadRequest());
        }
    }
}
