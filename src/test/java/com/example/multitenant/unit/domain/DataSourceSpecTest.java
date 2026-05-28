package com.example.multitenant.unit.domain;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.DataSourceSpec;
import com.example.multitenant.domain.tenant.InvalidEssentialArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link DataSourceSpec} 값 객체 단위 테스트.
 */
@SmallTest
@DisplayName("DataSourceSpec 값 객체")
class DataSourceSpecTest {

    private static final String VALID_URL  = "jdbc:postgresql://localhost:5432/db";
    private static final String VALID_USER = "user";
    private static final String VALID_PASS = "pass";

    @Test
    @DisplayName("유효한 값으로 생성하면 필드를 반환한다")
    void constructor_validValues_storesFields() {
        DataSourceSpec spec = new DataSourceSpec(VALID_URL, VALID_USER, VALID_PASS);
        assertThat(spec.url()).isEqualTo(VALID_URL);
        assertThat(spec.username()).isEqualTo(VALID_USER);
        assertThat(spec.password()).isEqualTo(VALID_PASS);
    }

    @Test
    @DisplayName("url 이 blank 이면 InvalidEssentialArgumentException 발생")
    void constructor_blankUrl_throwsException() {
        assertThatThrownBy(() -> new DataSourceSpec("  ", VALID_USER, VALID_PASS))
                .isInstanceOf(InvalidEssentialArgumentException.class);
    }

    @Test
    @DisplayName("username 이 null 이면 InvalidEssentialArgumentException 발생")
    void constructor_nullUsername_throwsException() {
        assertThatThrownBy(() -> new DataSourceSpec(VALID_URL, null, VALID_PASS))
                .isInstanceOf(InvalidEssentialArgumentException.class);
    }

    @Test
    @DisplayName("password 가 blank 이면 InvalidEssentialArgumentException 발생")
    void constructor_blankPassword_throwsException() {
        assertThatThrownBy(() -> new DataSourceSpec(VALID_URL, VALID_USER, ""))
                .isInstanceOf(InvalidEssentialArgumentException.class);
    }

    @Test
    @DisplayName("같은 값이면 동등하다")
    void equals_sameValues_isEqual() {
        assertThat(new DataSourceSpec(VALID_URL, VALID_USER, VALID_PASS))
                .isEqualTo(new DataSourceSpec(VALID_URL, VALID_USER, VALID_PASS));
    }
}
