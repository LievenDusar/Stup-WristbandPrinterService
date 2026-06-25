package com.stup.wristbandprinter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Profile("!worker")
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    // ROLE_ADMIN: full access (admin key or admin cookie). ROLE_PRINT: limited to the print/preview
    // endpoints (the optional print-only key, safe to expose in a browser since it cannot reach the
    // admin UI or destructive actions). Endpoint-to-role mapping lives in SecurityConfig.
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_PRINT = "ROLE_PRINT";

    private final byte[] adminKeyBytes;
    private final byte[] printKeyBytes; // null when no print-only key is configured
    private final AuthCookieService cookieService;

    public ApiKeyAuthFilter(@Value("${security.api-key}") String apiKey,
                            @Value("${security.print-api-key:}") String printApiKey,
                            AuthCookieService cookieService) {
        this.adminKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
        this.printKeyBytes = (printApiKey == null || printApiKey.isBlank())
            ? null
            : printApiKey.getBytes(StandardCharsets.UTF_8);
        this.cookieService = cookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (keyMatches(request, adminKeyBytes) || cookieValid(request)) {
            authenticate(ROLE_ADMIN);
        } else if (printKeyBytes != null && keyMatches(request, printKeyBytes)) {
            authenticate(ROLE_PRINT);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "api-client", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private boolean keyMatches(HttpServletRequest request, byte[] expected) {
        String key = request.getHeader("X-API-Key");
        return key != null
            && MessageDigest.isEqual(expected, key.getBytes(StandardCharsets.UTF_8));
    }

    private boolean cookieValid(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (AuthCookieService.COOKIE_NAME.equals(cookie.getName())) {
                return cookieService.isValid(cookie.getValue());
            }
        }
        return false;
    }
}
