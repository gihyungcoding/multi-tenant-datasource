package com.example.multitenant.demo.infrastructure;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 데모 메시지 JPA 엔티티.
 * 영속성 관심사를 도메인으로부터 격리하기 위해 infrastructure 계층에만 위치한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
@Entity
@Table(name = "demo_message")
class DemoMessageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "demo_message_seq")
    @SequenceGenerator(name = "demo_message_seq", sequenceName = "demo_message_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected DemoMessageJpaEntity() {}

    DemoMessageJpaEntity(String tenantId, String content, LocalDateTime createdAt) {
        this.tenantId  = tenantId;
        this.content   = content;
        this.createdAt = createdAt;
    }

    Long getId()                 { return id; }
    String getTenantId()         { return tenantId; }
    String getContent()          { return content; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
