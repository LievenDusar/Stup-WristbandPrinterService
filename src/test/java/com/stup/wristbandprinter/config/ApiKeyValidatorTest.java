package com.stup.wristbandprinter.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyValidatorTest {

    @Test
    void prodWorkerWithBlankAdminPassword_passes() {
        // A printer-worker (prod,worker) has no admin UI, so a missing admin password must not block startup.
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod", "worker");
        ApiKeyValidator validator = new ApiKeyValidator(env, "a-real-secret-key", new AdminProperties(), "");
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void prodNonWorkerWithBlankAdminPassword_throws() {
        // The management service (prod, no worker) still requires an admin password.
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        ApiKeyValidator validator = new ApiKeyValidator(env, "a-real-secret-key", new AdminProperties(), "a-cookie-secret");
        assertThatThrownBy(validator::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin");
    }

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

    @Test
    void prodWithBlankAdminPassword_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validateAdminPassword(true, "  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin");
    }

    @Test
    void prodWithRealAdminPassword_passes() {
        assertThatCode(() -> ApiKeyValidator.validateAdminPassword(true, "a-real-password"))
            .doesNotThrowAnyException();
    }

    @Test
    void nonProdWithBlankAdminPassword_passes() {
        assertThatCode(() -> ApiKeyValidator.validateAdminPassword(false, ""))
            .doesNotThrowAnyException();
    }

    @Test
    void prodWithBlankCookieSecret_throws() {
        assertThatThrownBy(() -> ApiKeyValidator.validateCookieSecret(true, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cookie-secret");
    }

    @Test
    void prodWithRealCookieSecret_passes() {
        assertThatCode(() -> ApiKeyValidator.validateCookieSecret(true, "a-real-secret"))
            .doesNotThrowAnyException();
    }

    @Test
    void nonProdWithBlankCookieSecret_passes() {
        assertThatCode(() -> ApiKeyValidator.validateCookieSecret(false, ""))
            .doesNotThrowAnyException();
    }
}
