package com.example.multitenant.annotation;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.*;

/**
 * Google "How Google Tests Software" — Small 테스트 마커.
 *
 * <p><b>제약:</b>
 * <ul>
 *   <li>네트워크·파일시스템·데이터베이스 I/O 금지</li>
 *   <li>외부 프로세스 의존 금지</li>
 *   <li>Spring 컨텍스트 로딩 금지</li>
 *   <li>실행 시간: 수 ms 이내</li>
 * </ul>
 *
 * <p><b>대상:</b> 도메인 엔티티, 값 객체, Mockito 기반 서비스 단위 테스트
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("small")
public @interface SmallTest {
}
