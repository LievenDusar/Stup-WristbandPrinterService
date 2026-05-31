package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.AdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiKeyAuthFilterTest {

    private final AdminProperties admin = adminWithPassword();
    private final AuthCookieService cookieService =
        new AuthCookieService("test-signing-secret", admin);
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter("test-key", cookieService);

    private static AdminProperties adminWithPassword() {
        AdminProperties a = new AdminProperties();
        a.setPassword("local-admin");
        return a;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validHeader_authenticates() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-API-Key", "test-key");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void validCookie_authenticates() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthCookieService.COOKIE_NAME, cookieService.issueToken()));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void noCredentials_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
