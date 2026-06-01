package com.stup.wristbandprinter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final byte[] apiKeyBytes;
    private final AuthCookieService cookieService;

    public ApiKeyAuthFilter(@Value("${security.api-key}") String apiKey,
                            AuthCookieService cookieService) {
        this.apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
        this.cookieService = cookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (headerMatches(request) || cookieValid(request)) {
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("api-client", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private boolean headerMatches(HttpServletRequest request) {
        String key = request.getHeader("X-API-Key");
        return key != null
            && MessageDigest.isEqual(apiKeyBytes, key.getBytes(StandardCharsets.UTF_8));
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
