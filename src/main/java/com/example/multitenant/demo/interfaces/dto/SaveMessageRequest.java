package com.example.multitenant.demo.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 데모 메시지 저장 HTTP 요청 DTO.
 *
 * @author gihyung.lee
 * @since 2026-05-28
 */
public record SaveMessageRequest(@NotBlank String content) {
}
