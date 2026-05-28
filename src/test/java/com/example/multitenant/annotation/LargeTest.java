package com.example.multitenant.annotation;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.*;

/**
 * Google "How Google Tests Software" — Large 테스트 마커.
 *
 * <p><b>특징:</b>
 * <ul>
 *   <li>실제 외부 서비스 사용 (Testcontainers PostgreSQL)</li>
 *   <li>전체 Spring 컨텍스트 로딩</li>
 *   <li>다중 프로세스 허용 (Docker 컨테이너)</li>
 *   <li>실행 시간: 수십 초</li>
 * </ul>
 *
 * <p><b>대상:</b> DataSource 라우팅 End-to-End, 동시성, 컨텍스트 로드 검증
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("large")
public @interface LargeTest {
}
