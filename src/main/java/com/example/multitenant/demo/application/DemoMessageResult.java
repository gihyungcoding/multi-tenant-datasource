package com.example.multitenant.demo.application;

import com.example.multitenant.demo.domain.DemoMessage;

import java.time.LocalDateTime;

/**
 * 데모 메시지 유즈케이스 처리 결과.
 * 인바운드 포트에서 인터페이스 계층으로 반환되는 읽기 전용 DTO.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public record DemoMessageResult(
        Long id,
        String tenantId,
        String content,
        LocalDateTime createdAt
) {
    public static DemoMessageResult from(DemoMessage message) {
        return new DemoMessageResult(
                message.getId(),
                message.getTenantId(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
