package com.example.multitenant.demo.infrastructure;

import com.example.multitenant.demo.application.port.out.DemoMessagePort;
import com.example.multitenant.demo.domain.DemoMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link DemoMessagePort} 아웃바운드 포트 구현체.
 * 도메인 객체 ↔ JPA 엔티티 변환을 담당하며, 애플리케이션 계층은 이 클래스를 직접 알지 못한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
@Component
public class DemoMessageAdapter implements DemoMessagePort {

    private final DemoMessageRepository repository;

    public DemoMessageAdapter(DemoMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public DemoMessage save(DemoMessage message) {
        return toDomain(repository.save(toEntity(message)));
    }

    @Override
    public List<DemoMessage> findAll() {
        return repository.findAll()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    // ── 매핑 ──────────────────────────────────────────────────────

    private DemoMessageJpaEntity toEntity(DemoMessage message) {
        return new DemoMessageJpaEntity(
                message.getTenantId(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    private DemoMessage toDomain(DemoMessageJpaEntity entity) {
        return DemoMessage.restore(
                entity.getId(),
                entity.getTenantId(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }
}
