package com.example.multitenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 테넌트 엔티티
 * @author gihyung.lee
 * @since 2026-05-21
 */
@Entity
@Table(name = "tenant")
public class TenantJpaEntity {

    @Id
    private String tenantId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String status; // "ACTIVE", "SUSPENDED"

    private String suspendReason;

    protected TenantJpaEntity() {

    }

    public TenantJpaEntity(String tenantId, String url, String username, String password, String status, String suspendReason) {
        this.tenantId = tenantId;
        this.url = url;
        this.username = username;
        this.password = password;
        this.status = status;
        this.suspendReason = suspendReason;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getStatus() {
        return status;
    }

    String getSuspendReason(){ return suspendReason; }
}
