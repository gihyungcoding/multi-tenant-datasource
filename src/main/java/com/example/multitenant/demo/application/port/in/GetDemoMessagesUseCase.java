package com.example.multitenant.demo.application.port.in;

import com.example.multitenant.demo.application.DemoMessageResult;

import java.util.List;

/**
 * 데모 메시지 목록 조회 유즈케이스 인바운드 포트.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public interface GetDemoMessagesUseCase {
    List<DemoMessageResult> findAll();
}
