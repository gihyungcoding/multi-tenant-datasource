package com.example.multitenant.integration;

import com.example.multitenant.annotation.LargeTest;
import com.example.multitenant.application.port.in.RegisterTenantUseCase;
import com.example.multitenant.application.port.in.command.RegisterTenantCommand;
import com.example.multitenant.demo.application.DemoMessageResult;
import com.example.multitenant.demo.application.port.in.GetDemoMessagesUseCase;
import com.example.multitenant.demo.application.port.in.SaveDemoMessageUseCase;
import com.example.multitenant.demo.application.port.in.command.SaveDemoMessageCommand;
import com.example.multitenant.domain.context.TenantContextHolder;
import com.example.multitenant.domain.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데모 메시지 테넌트 격리 통합 테스트.
 *
 * <h2>검증 목표</h2>
 * Dual EntityManagerFactory 설정 후 {@code DemoMessageJpaEntity}가
 * 마스터 DB가 아닌 테넌트 DB(RoutingDataSource → 해당 테넌트의 HikariPool)에
 * 실제로 저장되는지 검증한다.
 *
 * <h2>격리 검증 방법</h2>
 * <ol>
 *   <li>tenant-a 와 tenant-b 를 각자의 PostgreSQL 컨테이너에 등록한다.</li>
 *   <li>tenant-a 컨텍스트로 메시지를 저장한다.</li>
 *   <li>tenant-b 컨텍스트로 메시지를 조회하면 결과가 비어 있어야 한다 (격리 확인).</li>
 *   <li>tenant-a 컨텍스트로 메시지를 조회하면 저장한 메시지만 보여야 한다.</li>
 * </ol>
 *
 * @author gihyung.lee
 * @since 2026-06-01
 */
@LargeTest
@DisplayName("DemoMessage 테넌트 격리 — Dual EMF 검증")
class DemoMessageIsolationTest extends IntegrationTestBase {

    @Autowired private RegisterTenantUseCase    registerTenantUseCase;
    @Autowired private SaveDemoMessageUseCase   saveUseCase;
    @Autowired private GetDemoMessagesUseCase   getUseCase;
    @Autowired private TenantContextHolder      contextHolder;

    private static final String TENANT_A = "demo-tenant-a";
    private static final String TENANT_B = "demo-tenant-b";
    private static boolean tenantsRegistered = false;

    @BeforeEach
    void setUp() {
        contextHolder.clear();
        if (!tenantsRegistered) {
            registerTenantUseCase.register(
                    new RegisterTenantCommand(TENANT_A, getTenantAUrl(), "tenant_a", "tenant_a"));
            registerTenantUseCase.register(
                    new RegisterTenantCommand(TENANT_B, getTenantBUrl(), "tenant_b", "tenant_b"));
            tenantsRegistered = true;
        }
    }

    @Test
    @DisplayName("tenant-a 에 저장된 메시지는 tenant-b 에서 보이지 않는다")
    void demoMessage_savedInTenantA_notVisibleInTenantB() {
        // given — tenant-a 에 메시지 저장 (고유 식별자 포함)
        String uniqueContent = "hello from tenant-a [isolation-check-" + System.nanoTime() + "]";
        contextHolder.setTenant(new TenantId(TENANT_A));
        saveUseCase.save(new SaveDemoMessageCommand(uniqueContent));
        contextHolder.clear();

        // when — tenant-b 에서 조회
        contextHolder.setTenant(new TenantId(TENANT_B));
        List<DemoMessageResult> tenantBMessages = getUseCase.findAll();
        contextHolder.clear();

        // then — tenant-b 에서 tenant-a 메시지가 보이면 안 됨
        // (다른 테스트가 남긴 데이터가 있을 수 있으므로 isEmpty() 대신 doesNotContain 사용)
        assertThat(tenantBMessages)
                .extracting(DemoMessageResult::content)
                .as("tenant-b 는 tenant-a 의 메시지를 볼 수 없어야 한다")
                .doesNotContain(uniqueContent);
    }

    @Test
    @DisplayName("tenant-a 에 저장된 메시지는 tenant-a 컨텍스트에서만 조회된다")
    void demoMessage_savedInTenantA_visibleOnlyInTenantA() {
        // given — tenant-a 에 메시지 저장
        contextHolder.setTenant(new TenantId(TENANT_A));
        DemoMessageResult saved = saveUseCase.save(new SaveDemoMessageCommand("isolation test message"));
        contextHolder.clear();

        // when — tenant-a 에서 조회
        contextHolder.setTenant(new TenantId(TENANT_A));
        List<DemoMessageResult> tenantAMessages = getUseCase.findAll();
        contextHolder.clear();

        // then
        assertThat(tenantAMessages)
                .as("tenant-a 의 메시지가 tenant-a 컨텍스트에서 조회되어야 한다")
                .isNotEmpty();
        assertThat(tenantAMessages)
                .extracting(DemoMessageResult::content)
                .contains(saved.content());
    }

    @Test
    @DisplayName("두 테넌트의 메시지는 각자의 DB 에 독립적으로 저장된다")
    void demoMessage_twoTenants_fullyIsolated() {
        // given
        contextHolder.setTenant(new TenantId(TENANT_A));
        saveUseCase.save(new SaveDemoMessageCommand("msg from A"));
        contextHolder.clear();

        contextHolder.setTenant(new TenantId(TENANT_B));
        saveUseCase.save(new SaveDemoMessageCommand("msg from B"));
        saveUseCase.save(new SaveDemoMessageCommand("another msg from B"));
        contextHolder.clear();

        // when
        contextHolder.setTenant(new TenantId(TENANT_A));
        List<DemoMessageResult> aMessages = getUseCase.findAll();
        contextHolder.clear();

        contextHolder.setTenant(new TenantId(TENANT_B));
        List<DemoMessageResult> bMessages = getUseCase.findAll();
        contextHolder.clear();

        // then
        assertThat(aMessages)
                .extracting(DemoMessageResult::content)
                .doesNotContain("msg from B", "another msg from B");
        assertThat(bMessages)
                .extracting(DemoMessageResult::content)
                .doesNotContain("msg from A");
        assertThat(bMessages)
                .extracting(DemoMessageResult::content)
                .contains("msg from B", "another msg from B");
    }
}
