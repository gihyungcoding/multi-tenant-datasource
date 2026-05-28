package com.example.multitenant.demo.application.port.in.command;

import com.example.multitenant.domain.utils.ValidateUtil;

/**
 * 데모 메시지 저장 커맨드.
 * 인터페이스 계층에서 애플리케이션 계층으로 전달되는 입력 객체.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public record SaveDemoMessageCommand(String content) {
    public SaveDemoMessageCommand {
        ValidateUtil.validate("content", content);
    }
}
