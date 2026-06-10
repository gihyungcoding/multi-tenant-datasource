package com.example.multitenant.unit.infrastructure;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.infrastructure.datasource.TenantSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantSchemaInitializer} 단위 테스트.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>{@link TenantSchemaInitializer#initialize} — DataSource 접속 실패 시 예외 전파</li>
 * </ul>
 *
 * <h2>slave 스키마 초기화 테스트 제외 이유</h2>
 * slave 는 Streaming Replica 로만 운영한다.
 * PostgreSQL WAL 스트림이 master DDL 을 자동 전파하므로 애플리케이션은 slave 에 DDL 을 실행하지 않는다.
 * 따라서 slave 관련 초기화 로직이 존재하지 않으며 테스트할 대상도 없다.
 */
@SmallTest
@DisplayName("TenantSchemaInitializer")
class TenantSchemaInitializerTest {

    private final TenantSchemaInitializer initializer = new TenantSchemaInitializer();

    @Nested
    @DisplayName("initialize() — master DataSource")
    class Initialize {

        @Test
        @DisplayName("DataSource 접속 실패 시 예외가 전파된다")
        void initialize_connectionFailure_propagatesException() throws SQLException {
            DataSource failingDs = mock(DataSource.class);
            when(failingDs.getConnection()).thenThrow(new SQLException("connection refused"));

            assertThatThrownBy(() -> initializer.initialize(failingDs))
                    .isInstanceOf(DataAccessException.class);
        }
    }
}
