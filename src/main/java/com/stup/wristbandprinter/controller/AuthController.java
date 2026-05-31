package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.security.AuthCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/wristbands")
@Tag(name = "Authentication", description = "Admin login for the jobs page")
@SecurityRequirements({})
public class AuthController {

    public record LoginRequest(String username, String password) {}

    private final AdminProperties admin;
    private final AuthCookieService cookieService;
    private final boolean secureCookie;
    private final String sameSite;

    public AuthController(AdminProperties admin,
                          AuthCookieService cookieService,
                          @Value("${security.cookie.secure:true}") boolean secureCookie,
                          @Value("${security.cookie.same-site:Strict}") String sameSite) {
        this.admin = admin;
        this.cookieService = cookieService;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
    }

    @PostMapping("/login")
    @Operation(summary = "Admin login — sets an HttpOnly session cookie")
    public ResponseEntity<Void> login(@RequestBody LoginRequest request) {
        if (!credentialsMatch(request)) {
            return ResponseEntity.status(401).build();
        }
        ResponseCookie cookie = baseCookie(cookieService.issueToken())
            .maxAge(cookieService.validitySeconds())
            .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    @PostMapping("/logout")
    @Operation(summary = "Admin logout — clears the session cookie")
    public ResponseEntity<Void> logout() {
        ResponseCookie cookie = baseCookie("").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(AuthCookieService.COOKIE_NAME, value)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite(sameSite)
            .path("/");
    }

    private boolean credentialsMatch(LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null
            || admin.getPassword() == null) {
            return false;
        }
        boolean userOk = MessageDigest.isEqual(
            admin.getUsername().getBytes(StandardCharsets.UTF_8),
            request.username().getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(
            admin.getPassword().getBytes(StandardCharsets.UTF_8),
            request.password().getBytes(StandardCharsets.UTF_8));
        return userOk && passOk;
    }
}
