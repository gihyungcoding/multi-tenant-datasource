package com.example.multitenant.demo.application.port.out;

import com.example.multitenant.demo.domain.DemoMessage;

import java.util.List;

/**
 * 데모 메시지 영속성 아웃바운드 포트.
 * 구현체는 infrastructure 계층의 {@link com.example.multitenant.demo.infrastructure.DemoMessageAdapter} 에 위치한다.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public interface DemoMessagePort {
    DemoMessage save(DemoMessage message);
    List<DemoMessage> findAll();
}
