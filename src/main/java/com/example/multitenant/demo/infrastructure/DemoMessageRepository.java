package com.example.multitenant.demo.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 데모 메시지 Spring Data JPA 리포지토리.
 * 패키지-프라이빗으로 선언하여 {@link DemoMessageAdapter} 만 접근 가능하도록 캡슐화한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
interface DemoMessageRepository extends JpaRepository<DemoMessageJpaEntity, Long> {
}
