package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import com.example.multitenant.infrastructure.datasource.RoutingDataSource;
import com.example.multitenant.infrastructure.exception.TenantContextMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link RoutingDataSource#determineCurrentLookupKey()} 의 Master/Slave 라우팅 단위 테스트.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>slave 미등록 시 readOnly 트랜잭션도 master 로 라우팅</li>
 *   <li>slave 등록 시 readOnly 트랜잭션은 slave 로 라우팅</li>
 *   <li>slave 등록 시 쓰기 트랜잭션은 master 로 라우팅</li>
 *   <li>테넌트 컨텍스트 없으면 예외 발생</li>
 * </ul>
 */
@SmallTest
@DisplayName("RoutingDataSource - Master/Slave 라우팅")
class RoutingDataSourceMasterSlaveTest {

    private static final TenantId TENANT_A = new TenantId("tenant-a");

    /** 테스트용 라우팅 키 추출 — protected determineCurrentLookupKey() 를 공개 */
    static class InspectableRoutingDataSource extends RoutingDataSource {
        InspectableRoutingDataSource(TenantContextHolder ctx) {
            super(ctx);
        }

        String lookupKey() {
            return (String) determineCurrentLookupKey();
        }
    }

    private InspectableRoutingDataSource routing;
    private TenantId currentTenant;

    @BeforeEach
    void setUp() {
        TenantContextHolder ctx = new TenantContextHolder() {
            @Override public void setTenant(TenantId id) { currentTenant = id; }
            @Override public TenantId getTenant()        { return currentTenant; }
            @Override public boolean hasTenant()         { return currentTenant != null; }
            @Override public void clear()                { currentTenant = null; }
        };
        routing = new InspectableRoutingDataSource(ctx);
        ctx.setTenant(TENANT_A);
    }

    // ── slave 미구성 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("slave 미구성 (master 단독)")
    class WithoutSlave {

        @BeforeEach
        void setUpRouting() {
            routing.refresh(Map.of("tenant-a:master", mock(DataSource.class)));
        }

        @Test
        @DisplayName("쓰기 트랜잭션은 master 로 라우팅된다")
        void writeTx_routesToMaster() {
            assertThat(routing.lookupKey()).isEqualTo("tenant-a:master");
        }

        @Test
        @DisplayName("readOnly 트랜잭션도 slave 없으면 master 로 라우팅된다")
        void readOnlyTx_noSlave_routesToMaster() {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try {
                assertThat(routing.lookupKey()).isEqualTo("tenant-a:master");
            } finally {
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
        }
    }

    // ── slave 구성 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("slave 구성")
    class WithSlave {

        @BeforeEach
        void setUpRouting() {
            routing.refresh(Map.of(
                    "tenant-a:master", mock(DataSource.class),
                    "tenant-a:slave",  mock(DataSource.class)
            ));
        }

        @Test
        @DisplayName("쓰기 트랜잭션은 master 로 라우팅된다")
        void writeTx_routesToMaster() {
            assertThat(routing.lookupKey()).isEqualTo("tenant-a:master");
        }

        @Test
        @DisplayName("readOnly 트랜잭션은 slave 로 라우팅된다")
        void readOnlyTx_routesToSlave() {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try {
                assertThat(routing.lookupKey()).isEqualTo("tenant-a:slave");
            } finally {
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
        }
    }

    // ── 컨텍스트 없음 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("테넌트 컨텍스트가 없으면 TenantContextMissingException 발생")
    void noContext_throwsException() {
        routing.refresh(Map.of("tenant-a:master", mock(DataSource.class)));

        // 컨텍스트 제거
        TenantContextHolder emptyCtx = new TenantContextHolder() {
            @Override public void setTenant(TenantId id) {}
            @Override public TenantId getTenant()        { return null; }
            @Override public boolean hasTenant()         { return false; }
            @Override public void clear()                {}
        };
        InspectableRoutingDataSource noCtxRouting = new InspectableRoutingDataSource(emptyCtx);
        noCtxRouting.refresh(Map.of("tenant-a:master", mock(DataSource.class)));

        assertThatThrownBy(noCtxRouting::lookupKey)
                .isInstanceOf(TenantContextMissingException.class);
    }

    // ── refresh 후 키 갱신 검증 ───────────────────────────────────────────────

    @Test
    @DisplayName("refresh 후 slave 키가 추가되면 readOnly 트랜잭션이 slave 로 전환된다")
    void refresh_addsSlave_readOnlyTxSwitchesToSlave() {
        routing.refresh(Map.of("tenant-a:master", mock(DataSource.class)));

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(routing.lookupKey()).isEqualTo("tenant-a:master"); // slave 없음

            routing.refresh(Map.of(
                    "tenant-a:master", mock(DataSource.class),
                    "tenant-a:slave",  mock(DataSource.class)
            ));

            assertThat(routing.lookupKey()).isEqualTo("tenant-a:slave"); // slave 추가됨
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }
}
