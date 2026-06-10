package com.example.multitenant.application.port.out;

import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.TenantId;

import java.util.List;

/**
 * @author gihyung.lee
 * @since 2026-05-21
 */
public interface TenantDataSourcePort {
    /**
     * 테넌트 DataSource 를 등록한다.
     *
     * @param masterSpec  master DataSource 접속 정보
     * @param slaveSpecs  slave DataSource 접속 정보 목록. 비어있으면 master 단독 운영.
     */
    void register(TenantId id, DataSourceSpec masterSpec, List<DataSourceSpec> slaveSpecs);
    /** 라우팅 테이블에서 제거하고 HikariCP 풀을 종료한다. */
    void deregister(TenantId id);
    boolean isRegistered(TenantId id);
    void refreshRoutingTable();
}
