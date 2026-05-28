package com.example.multitenant.demo.domain;

import java.time.LocalDateTime;

/**
 * 테넌트 라우팅 검증용 도메인 엔티티.
 * JPA 의존 없이 순수 Java로 구성된다.
 * 각 테넌트 DB에 동일 테이블이 생성되어 DataSource 라우팅 결과로 격리됨을 확인한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public class DemoMessage {

    private final Long id;           // 저장 전: null
    private final String tenantId;
    private final String content;
    private final LocalDateTime createdAt;

    private DemoMessage(Long id, String tenantId, String content, LocalDateTime createdAt) {
        this.id        = id;
        this.tenantId  = tenantId;
        this.content   = content;
        this.createdAt = createdAt;
    }

    /** 신규 메시지 생성 — id 는 저장 시 DB 에서 부여된다. */
    public static DemoMessage create(String tenantId, String content) {
        return new DemoMessage(null, tenantId, content, LocalDateTime.now());
    }

    /** 영속성 계층에서 복원할 때 사용한다. */
    public static DemoMessage restore(Long id, String tenantId,
                                       String content, LocalDateTime createdAt) {
        return new DemoMessage(id, tenantId, content, createdAt);
    }

    public Long getId()                 { return id; }
    public String getTenantId()         { return tenantId; }
    public String getContent()          { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
