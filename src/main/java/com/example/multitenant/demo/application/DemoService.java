package com.example.multitenant.demo.application;

import com.example.multitenant.demo.application.port.in.GetDemoMessagesUseCase;
import com.example.multitenant.demo.application.port.in.SaveDemoMessageUseCase;
import com.example.multitenant.demo.application.port.in.command.SaveDemoMessageCommand;
import com.example.multitenant.demo.application.port.out.DemoMessagePort;
import com.example.multitenant.demo.domain.DemoMessage;
import com.example.multitenant.domain.context.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 데모 메시지 애플리케이션 서비스.
 * {@link SaveDemoMessageUseCase}, {@link GetDemoMessagesUseCase} 인바운드 포트를 구현하며
 * {@link DemoMessagePort} 아웃바운드 포트만 참조한다 — 인프라 구현체를 직접 알지 못한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
@Service
public class DemoService implements SaveDemoMessageUseCase, GetDemoMessagesUseCase {

    private final DemoMessagePort demoMessagePort;
    private final TenantContextHolder contextHolder;

    public DemoService(DemoMessagePort demoMessagePort, TenantContextHolder contextHolder) {
        this.demoMessagePort = demoMessagePort;
        this.contextHolder   = contextHolder;
    }

    @Override
    @Transactional("tenantTransactionManager")
    public DemoMessageResult save(SaveDemoMessageCommand command) {
        String tenantId = contextHolder.getTenant().value();
        DemoMessage saved = demoMessagePort.save(DemoMessage.create(tenantId, command.content()));
        return DemoMessageResult.from(saved);
    }

    @Override
    @Transactional(value = "tenantTransactionManager", readOnly = true)
    public List<DemoMessageResult> findAll() {
        return demoMessagePort.findAll()
                .stream()
                .map(DemoMessageResult::from)
                .toList();
    }
}
