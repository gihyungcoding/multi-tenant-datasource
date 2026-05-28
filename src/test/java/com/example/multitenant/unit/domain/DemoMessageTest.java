package com.example.multitenant.unit.domain;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.demo.domain.DemoMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link DemoMessage} 도메인 엔티티 단위 테스트.
 */
@SmallTest
@DisplayName("DemoMessage 도메인")
class DemoMessageTest {

    @Test
    @DisplayName("create() 는 id null, tenantId·content 설정, createdAt 자동 세팅")
    void create_setsFieldsAndNullId() {
        DemoMessage msg = DemoMessage.create("tenant-a", "hello");

        assertThat(msg.getId()).isNull();
        assertThat(msg.getTenantId()).isEqualTo("tenant-a");
        assertThat(msg.getContent()).isEqualTo("hello");
        assertThat(msg.getCreatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("restore() 는 id 포함 모든 필드를 그대로 복원한다")
    void restore_setsAllFields() {
        LocalDateTime ts  = LocalDateTime.of(2026, 5, 28, 9, 0);
        DemoMessage msg   = DemoMessage.restore(42L, "tenant-b", "world", ts);

        assertThat(msg.getId()).isEqualTo(42L);
        assertThat(msg.getTenantId()).isEqualTo("tenant-b");
        assertThat(msg.getContent()).isEqualTo("world");
        assertThat(msg.getCreatedAt()).isEqualTo(ts);
    }
}
