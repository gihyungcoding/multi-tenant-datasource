package com.example.multitenant.domain.tenant;

import com.example.multitenant.domain.utils.ValidateUtil;

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
        ValidateUtil.validate("url", url);
        ValidateUtil.validate("username", username);
        ValidateUtil.validate("password", password);
    }

}
