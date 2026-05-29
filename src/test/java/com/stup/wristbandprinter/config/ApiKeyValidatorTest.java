package com.stup.wristbandprinter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyValidatorTest {

    @Test
    void prodWithDefaultKey_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validate(true, "changeme"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("security.api-key");
    }

    @Test
    void prodWithBlankKey_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validate(true, "   "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithNullKey_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validate(true, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithUnresolvedPlaceholder_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validate(true, "${SECURITY_API_KEY}"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithRealKey_passes() {
        assertThatCode(() -> ApiKeyValidator.validate(true, "a-real-secret-key"))
            .doesNotThrowAnyException();
    }

    @Test
    void nonProdWithDefaultKey_passes() {
        assertThatCode(() -> ApiKeyValidator.validate(false, "changeme"))
            .doesNotThrowAnyException();
    }
}
