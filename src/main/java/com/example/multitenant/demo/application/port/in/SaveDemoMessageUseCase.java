package com.example.multitenant.demo.application.port.in;

import com.example.multitenant.demo.application.DemoMessageResult;
import com.example.multitenant.demo.application.port.in.command.SaveDemoMessageCommand;

/**
 * 데모 메시지 저장 유즈케이스 인바운드 포트.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public interface SaveDemoMessageUseCase {
    DemoMessageResult save(SaveDemoMessageCommand command);
}
