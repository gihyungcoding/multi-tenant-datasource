package com.example.multitenant.infrastructure.persistence;

import jakarta.persistence.*;

/**
 * 테넌트 Streaming Replica DataSource 엔티티.
 *
 * <p>테넌트 하나에 여러 replica 를 등록할 수 있으므로 {@code tenant} 와 별도 테이블로 분리한다.
 * {@code ordinal} 은 등록 순서를 보존하며, 라우팅 시 round-robin 순서의 기준이 된다.
 *
 * @author gihyung.lee
 * @since 2026-06-10
 */
@Entity
@Table(name = "tenant_replica",
       indexes = @Index(name = "idx_tenant_replica_tenant_id", columnList = "tenant_id"))
public class TenantReplicaJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** tenant 테이블의 tenant_id 를 참조한다 (FK 없이 논리적 연관). */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** 등록 순서 (0-based). round-robin 라우팅 시 정렬 기준. */
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    protected TenantReplicaJpaEntity() {}

    public TenantReplicaJpaEntity(String tenantId, int ordinal,
                                  String url, String username, String password) {
        this.tenantId = tenantId;
        this.ordinal  = ordinal;
        this.url      = url;
        this.username = username;
        this.password = password;
    }

    public Long   getId()       { return id; }
    public String getTenantId() { return tenantId; }
    public int    getOrdinal()  { return ordinal; }
    public String getUrl()      { return url; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
}
