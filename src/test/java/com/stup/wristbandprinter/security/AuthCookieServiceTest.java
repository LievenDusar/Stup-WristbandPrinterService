package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.AdminProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookieServiceTest {

    private AuthCookieService newService() {
        AdminProperties admin = new AdminProperties();
        admin.setPassword("local-admin");
        return new AuthCookieService("test-signing-secret", admin);
    }

    @Test
    void issuedToken_isValid() {
        AuthCookieService svc = newService();
        assertThat(svc.isValid(svc.issueToken())).isTrue();
    }

    @Test
    void tamperedToken_isInvalid() {
        AuthCookieService svc = newService();
        String token = svc.issueToken();
        String tampered = token.substring(0, token.length() - 1)
            + (token.endsWith("A") ? "B" : "A");
        assertThat(svc.isValid(tampered)).isFalse();
    }

    @Test
    void expiredToken_isInvalid() {
        AuthCookieService svc = newService();
        String expired = svc.sign(System.currentTimeMillis() - 1000); // already past
        assertThat(svc.isValid(expired)).isFalse();
    }

    @Test
    void nullOrGarbage_isInvalid() {
        AuthCookieService svc = newService();
        assertThat(svc.isValid(null)).isFalse();
        assertThat(svc.isValid("not-a-token")).isFalse();
    }
}
