# Jobs Page Redesign — Plan 2 of 3: Admin Authentication

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the jobs page an admin-only login backed by a dedicated credential (separate from the machine `X-API-Key`), exchanged at login for a stateless, signed, HttpOnly session cookie. Secure the SSE stream.

**Architecture:** A new `AuthCookieService` mints/validates a stateless signed token (`expiry.HMAC`). `AuthController` exposes `/login` (validates the admin credential, sets the cookie) and `/logout`. `ApiKeyAuthFilter` authenticates a request if it carries either a valid `X-API-Key` header (machine, unchanged) or a valid auth cookie (admin browser). `SecurityConfig` permits the static shells + `/login`, and now requires auth on `/jobs/stream`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Security, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-05-29-jobs-page-redesign-design.md`

**Prerequisite:** Plan 1 (backend job operations) is recommended first but not required — this plan is independent of it.

---

## File structure

- `config/AdminProperties.java` — new; `security.admin.username` / `password`.
- `security/AuthCookieService.java` — new; sign/verify the cookie token.
- `controller/AuthController.java` — new; `/login`, `/logout`.
- `security/ApiKeyAuthFilter.java` — modify; accept header **or** cookie.
- `config/SecurityConfig.java` — modify; permit static + `/login`, require auth on `/jobs/stream`.
- `config/ApiKeyValidator.java` — modify; guard admin password in prod.
- `application.yml` / `application-local.yml` / `application-prod.yml` — admin + cookie settings.
- Tests: `AuthCookieServiceTest` (new), `AuthControllerTest` (new), `ApiKeyValidatorTest` (modify).

---

### Task 1: AdminProperties + configuration

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/config/AdminProperties.java`
- Modify: `src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`

- [ ] **Step 1: Create the properties class**

Create `src/main/java/com/stup/wristbandprinter/config/AdminProperties.java`:

```java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.admin")
public class AdminProperties {

    private String username = "admin";
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
```

- [ ] **Step 2: Add base config in `application.yml`**

Under the existing top-level `security:` key (which has `api-key`), add the admin and cookie settings so the block reads:

```yaml
security:
  api-key: changeme  # Override via security.api-key env var or profile YAML in all real environments
  admin:
    username: admin
    # password supplied per-profile / via env (SECURITY_ADMIN_PASSWORD); never defaulted in base
  cookie:
    secure: true       # send cookie only over HTTPS (overridden in local)
    same-site: Strict  # overridden to Lax in local
  # cookie-secret: optional; if unset the cookie signing key is derived from the admin password
```

- [ ] **Step 3: Add local overrides in `application-local.yml`**

Merge under the existing `security:` key (which has `api-key: local-dev-key`) so local dev works over http://localhost:

```yaml
security:
  admin:
    password: local-admin
  cookie:
    secure: false
    same-site: Lax
```

- [ ] **Step 4: Add prod mapping in `application-prod.yml`**

Under the existing `security:` key (which has `api-key: ${SECURITY_API_KEY}`), add:

```yaml
security:
  admin:
    password: ${ADMIN_PASSWORD}
```

- [ ] **Step 5: Verify compile**

Run: `./mvnw -q -DskipTests test-compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/AdminProperties.java \
        src/main/resources/application.yml src/main/resources/application-local.yml \
        src/main/resources/application-prod.yml
git commit -m "feat: add admin credential and cookie configuration"
```

---

### Task 2: AuthCookieService (stateless signed token)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/security/AuthCookieService.java`
- Test: `src/test/java/com/stup/wristbandprinter/security/AuthCookieServiceTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/security/AuthCookieServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=AuthCookieServiceTest`
Expected: FAIL — compile error, `AuthCookieService` does not exist.

- [ ] **Step 3: Implement the service**

Create `src/main/java/com/stup/wristbandprinter/security/AuthCookieService.java`:

```java
package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.AdminProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

@Component
public class AuthCookieService {

    public static final String COOKIE_NAME = "stup_admin";
    private static final Duration VALIDITY = Duration.ofHours(12);

    private final byte[] secret;

    public AuthCookieService(@Value("${security.cookie-secret:}") String cookieSecret,
                             AdminProperties adminProperties) {
        String source = (cookieSecret != null && !cookieSecret.isBlank())
            ? cookieSecret
            : adminProperties.getPassword();
        if (source == null || source.isBlank()) {
            // No signing material available; tokens cannot be minted/validated.
            // In prod the ApiKeyValidator guard prevents startup with a blank admin password.
            source = "uninitialized-secret";
        }
        this.secret = source.getBytes(StandardCharsets.UTF_8);
    }

    public long validitySeconds() {
        return VALIDITY.getSeconds();
    }

    public String issueToken() {
        return sign(System.currentTimeMillis() + VALIDITY.toMillis());
    }

    /** Package-private for tests: build a validly-signed token with an explicit expiry. */
    String sign(long expiryEpochMs) {
        String payload = Long.toString(expiryEpochMs);
        return payload + "." + hmac(payload);
    }

    public boolean isValid(String token) {
        if (token == null) {
            return false;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String payload = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        byte[] expected = hmac(payload).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            return Long.parseLong(payload) > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign auth token", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AuthCookieServiceTest`
Expected: PASS, `Tests run: 4`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/security/AuthCookieService.java \
        src/test/java/com/stup/wristbandprinter/security/AuthCookieServiceTest.java
git commit -m "feat: add stateless signed auth-cookie service"
```

---

### Task 3: AuthController (login/logout)

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/controller/AuthController.java`
- Test: `src/test/java/com/stup/wristbandprinter/controller/AuthControllerTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/controller/AuthControllerTest.java`:

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {
    "security.api-key=test-key",
    "security.admin.username=admin",
    "security.admin.password=s3cret",
    "security.cookie-secret=test-signing-secret",
    "security.cookie.secure=false",
    "security.cookie.same-site=Lax"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void login_correctCredentials_setsCookie() throws Exception {
        mockMvc.perform(post("/api/wristbands/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"s3cret\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("stup_admin=")))
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/wristbands/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsCookie() throws Exception {
        mockMvc.perform(post("/api/wristbands/logout"))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: FAIL — compile error, `AuthController` does not exist.

- [ ] **Step 3: Implement the controller**

Create `src/main/java/com/stup/wristbandprinter/controller/AuthController.java`:

```java
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
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: PASS, `Tests run: 3`. (This requires Task 4's filter change only if the import wiring needs it; if `ApiKeyAuthFilter` does not yet accept an `AuthCookieService` constructor arg, this test still compiles because `@Import(AuthCookieService.class)` provides the bean. If the test fails to construct `ApiKeyAuthFilter`, complete Task 4 first, then re-run.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/AuthController.java \
        src/test/java/com/stup/wristbandprinter/controller/AuthControllerTest.java
git commit -m "feat: add admin login/logout endpoints"
```

---

### Task 4: ApiKeyAuthFilter accepts the auth cookie

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/security/ApiKeyAuthFilter.java`
- Test: `src/test/java/com/stup/wristbandprinter/security/ApiKeyAuthFilterTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/security/ApiKeyAuthFilterTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ApiKeyAuthFilterTest`
Expected: FAIL — compile error, the `ApiKeyAuthFilter` constructor does not yet take an `AuthCookieService`.

- [ ] **Step 3: Update the filter**

Replace the contents of `src/main/java/com/stup/wristbandprinter/security/ApiKeyAuthFilter.java`:

```java
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
```

- [ ] **Step 4: Run the test**

Run: `./mvnw test -Dtest=ApiKeyAuthFilterTest`
Expected: PASS, `Tests run: 3`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/security/ApiKeyAuthFilter.java \
        src/test/java/com/stup/wristbandprinter/security/ApiKeyAuthFilterTest.java
git commit -m "feat: accept admin auth cookie in the API key filter"
```

---

### Task 5: SecurityConfig — permit static + login, secure the SSE stream

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java`
- Test: `src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java`:

```java
package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandLayoutService;
import com.stup.wristbandprinter.service.ZplGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PrintQueueService printQueueService;
    @MockitoBean private WristbandLayoutService wristbandLayoutService;
    @MockitoBean private ZplGeneratorService zplGeneratorService;
    @MockitoBean private LabelaryPreviewService labelaryPreviewService;

    @Test
    void sseStream_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/wristbands/jobs/stream"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpoint_isPublic() throws Exception {
        // permitAll: reaches the controller (no AuthController here, so 404/405, NOT 401)
        mockMvc.perform(get("/api/wristbands/login"))
            .andExpect(status().is(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED.value()));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=SecurityConfigTest`
Expected: FAIL — `sseStream_requiresAuth` gets `200`/permitted (stream currently in `permitAll`).

- [ ] **Step 3: Update SecurityConfig**

In `SecurityConfig.java`, replace the `authorizeHttpRequests` block's `requestMatchers(...).permitAll()` list so it reads:

```java
            .authorizeHttpRequests(auth -> auth
                // Static admin UI shells and the login endpoint are public; all DATA
                // endpoints (including the SSE stream) require the API key or admin cookie.
                .requestMatchers(
                    "/jobs.html",
                    "/login.html",
                    "/css/**",
                    "/js/**",
                    "/api/wristbands/login",
                    "/api/wristbands/logout",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            )
```

(Note: `/api/wristbands/jobs/stream` is intentionally NOT listed — it now requires authentication.)

- [ ] **Step 4: Run the test**

Run: `./mvnw test -Dtest=SecurityConfigTest`
Expected: PASS, `Tests run: 2`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java \
        src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java
git commit -m "feat: secure SSE stream and permit admin UI static assets + login"
```

---

### Task 6: Prod startup guard for the admin password

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/config/ApiKeyValidator.java`
- Test: `src/test/java/com/stup/wristbandprinter/config/ApiKeyValidatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ApiKeyValidatorTest`:

```java
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
```

(`assertThatCode` is already imported in this test class.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=ApiKeyValidatorTest`
Expected: FAIL — `validateAdminPassword` does not exist.

- [ ] **Step 3: Extend ApiKeyValidator**

In `ApiKeyValidator.java`: inject `AdminProperties`, validate its password in `afterPropertiesSet`, and add the static method. Concretely —

Change the constructor and field set to also hold `AdminProperties`:

```java
    private final Environment environment;
    private final String apiKey;
    private final AdminProperties adminProperties;

    public ApiKeyValidator(Environment environment,
                           @Value("${security.api-key:}") String apiKey,
                           AdminProperties adminProperties) {
        this.environment = environment;
        this.apiKey = apiKey;
        this.adminProperties = adminProperties;
    }
```

Update `afterPropertiesSet`:

```java
    @Override
    public void afterPropertiesSet() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        validate(prod, apiKey);
        validateAdminPassword(prod, adminProperties.getPassword());
    }
```

Add the new static method next to the existing `validate`:

```java
    static void validateAdminPassword(boolean prodProfileActive, String adminPassword) {
        if (!prodProfileActive) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                "security.admin.password must be set when running with the 'prod' profile. "
                    + "Set the ADMIN_PASSWORD environment variable.");
        }
    }
```

- [ ] **Step 4: Run the test, then the full suite**

Run: `./mvnw test -Dtest=ApiKeyValidatorTest`
Expected: PASS.
Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/ApiKeyValidator.java \
        src/test/java/com/stup/wristbandprinter/config/ApiKeyValidatorTest.java
git commit -m "feat: fail fast on blank admin password under prod profile"
```

---

## Self-review

**Spec coverage (auth portion):**
- Dedicated admin credential separate from machine key → `AdminProperties` (Task 1), validated in `AuthController` (Task 3). ✓
- `POST /login` sets HttpOnly cookie (Secure/SameSite per profile); wrong creds → 401 → Task 3. ✓
- `POST /logout` clears cookie → Task 3. ✓
- Stateless signed token (`expiry.HMAC`), secret from `security.cookie-secret` or derived from admin password → `AuthCookieService` (Task 2). ✓
- Filter accepts header OR cookie; machine path unchanged → Task 4. ✓
- SecurityConfig permits static shells + `/login`; SSE stream now requires auth → Task 5. ✓
- Prod startup guard for admin password → Task 6. ✓

**Placeholder scan:** No TBD/TODO; every code step is complete with exact commands.

**Type consistency:** `AuthCookieService.COOKIE_NAME` / `issueToken()` / `isValid()` / `sign()` / `validitySeconds()` used consistently across the service (Task 2), filter (Task 4), and controller (Task 3). `AdminProperties.getUsername()/getPassword()` used in Tasks 1, 3, 6. `validateAdminPassword(boolean,String)` defined and called in Task 6.

**Cross-plan note:** Plan 3 (frontend) consumes `/login`, `/logout`, the secured SSE stream, and the cookie established here.
