package com.example.multitenant.annotation;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.*;

/**
 * Google "How Google Tests Software" — Medium 테스트 마커.
 *
 * <p><b>제약:</b>
 * <ul>
 *   <li>단일 머신 내 localhost 네트워크 허용</li>
 *   <li>실제 외부 서비스(원격 DB 등) 금지</li>
 *   <li>Spring Slice 컨텍스트(@WebMvcTest 등) 허용</li>
 *   <li>실행 시간: 수 초 이내</li>
 * </ul>
 *
 * <p><b>대상:</b> @WebMvcTest 컨트롤러 테스트 (MockMvc + Mock 의존성)
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("medium")
public @interface MediumTest {
}
