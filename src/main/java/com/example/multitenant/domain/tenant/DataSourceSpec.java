package com.example.multitenant.domain.tenant;

/**
 * DB 접속정보
 * @author gihyung.lee
 * @since 2026-05-21
 */
public record DataSourceSpec(
        String url,
        String username,
        String password
) {
    public DataSourceSpec {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url must not be empty");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username must not be empty");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("password must not be empty");
    }
}
