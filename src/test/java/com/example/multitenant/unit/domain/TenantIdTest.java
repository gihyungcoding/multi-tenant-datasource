package com.example.multitenant.unit.domain;

import com.example.multitenant.annotation.SmallTest;
import com.example.multitenant.domain.tenant.InvalidEssentialArgumentException;
import com.example.multitenant.domain.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link TenantId} 값 객체 단위 테스트.
 */
@SmallTest
@DisplayName("TenantId 값 객체")
class TenantIdTest {

    @Test
    @DisplayName("유효한 값으로 생성하면 value() 를 반환한다")
    void constructor_validValue_returnsValue() {
        TenantId id = new TenantId("tenant-a");
        assertThat(id.value()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("같은 값이면 동등하다")
    void equals_sameValue_isEqual() {
        assertThat(new TenantId("x")).isEqualTo(new TenantId("x"));
    }

    @ParameterizedTest(name = "value = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("null·빈 문자열·공백은 InvalidEssentialArgumentException 발생")
    void constructor_blankOrNull_throwsException(String value) {
        assertThatThrownBy(() -> new TenantId(value))
                .isInstanceOf(InvalidEssentialArgumentException.class);
    }
}
