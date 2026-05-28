package com.example.multitenant.unit.application;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.demo.application.DemoMessageResult;
import com.example.multitenant.demo.application.DemoService;
import com.example.multitenant.demo.application.port.in.command.SaveDemoMessageCommand;
import com.example.multitenant.demo.application.port.out.DemoMessagePort;
import com.example.multitenant.demo.domain.DemoMessage;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DemoService} 단위 테스트.
 * Spring 컨텍스트 없음. DemoMessagePort·TenantContextHolder 는 Mock 으로 교체.
 */
@SmallTest
@ExtendWith(MockitoExtension.class)
@DisplayName("DemoService")
class DemoServiceTest {

    @Mock private DemoMessagePort    demoMessagePort;
    @Mock private TenantContextHolder contextHolder;
    @InjectMocks private DemoService service;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    // ── save() ────────────────────────────────────────────────

    @Test
    @DisplayName("save() 는 contextHolder 에서 읽은 tenantId 로 DemoMessage 를 생성한다")
    void save_usesContextTenantId() {
        when(contextHolder.getTenant()).thenReturn(new TenantId("tenant-a"));
        DemoMessage persisted = DemoMessage.restore(1L, "tenant-a", "hello", NOW);
        when(demoMessagePort.save(any())).thenReturn(persisted);

        DemoMessageResult result = service.save(new SaveDemoMessageCommand("hello"));

        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("save() 는 demoMessagePort.save() 를 1회 호출한다")
    void save_invokesPortOnce() {
        when(contextHolder.getTenant()).thenReturn(new TenantId("tenant-a"));
        when(demoMessagePort.save(any())).thenReturn(
                DemoMessage.restore(1L, "tenant-a", "hello", NOW));

        service.save(new SaveDemoMessageCommand("hello"));

        verify(demoMessagePort, times(1)).save(any());
    }

    @Test
    @DisplayName("save() 의 반환 createdAt 은 null 이 아니다")
    void save_result_createdAtIsNotNull() {
        when(contextHolder.getTenant()).thenReturn(new TenantId("tenant-a"));
        when(demoMessagePort.save(any())).thenReturn(
                DemoMessage.restore(1L, "tenant-a", "hello", NOW));

        DemoMessageResult result = service.save(new SaveDemoMessageCommand("hello"));

        assertThat(result.createdAt()).isNotNull();
    }

    // ── findAll() ─────────────────────────────────────────────

    @Test
    @DisplayName("findAll() 은 포트가 반환한 메시지를 모두 DemoMessageResult 로 변환한다")
    void findAll_returnsAllMessagesAsDtos() {
        List<DemoMessage> messages = List.of(
                DemoMessage.restore(1L, "tenant-a", "msg-1", NOW),
                DemoMessage.restore(2L, "tenant-a", "msg-2", NOW)
        );
        when(demoMessagePort.findAll()).thenReturn(messages);

        List<DemoMessageResult> results = service.findAll();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("msg-1");
        assertThat(results.get(1).content()).isEqualTo("msg-2");
    }

    @Test
    @DisplayName("findAll() 에서 포트가 빈 리스트를 반환하면 빈 리스트를 반환한다")
    void findAll_emptyPort_returnsEmptyList() {
        when(demoMessagePort.findAll()).thenReturn(List.of());

        assertThat(service.findAll()).isEmpty();
    }
}
