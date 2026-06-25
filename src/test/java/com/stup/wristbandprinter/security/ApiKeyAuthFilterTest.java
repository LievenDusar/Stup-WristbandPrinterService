package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.AdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiKeyAuthFilterTest {

    private final AdminProperties admin = adminWithPassword();
    private final AuthCookieService cookieService =
        new AuthCookieService("test-signing-secret", admin);
    private final ApiKeyAuthFilter filter =
        new ApiKeyAuthFilter("admin-key", "print-key", cookieService);

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
    void adminHeader_authenticatesAsAdmin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-API-Key", "admin-key");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(roles()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void printHeader_authenticatesAsPrintOnly() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-API-Key", "print-key");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(roles()).containsExactly("ROLE_PRINT");
    }

    @Test
    void validCookie_authenticatesAsAdmin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthCookieService.COOKIE_NAME, cookieService.issueToken()));
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(roles()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void noCredentials_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void wrongKey_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-API-Key", "nope");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void blankPrintKey_neverMatchesEmptyHeader() throws Exception {
        ApiKeyAuthFilter noPrintKey = new ApiKeyAuthFilter("admin-key", "", cookieService);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-API-Key", "");
        noPrintKey.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static java.util.List<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
