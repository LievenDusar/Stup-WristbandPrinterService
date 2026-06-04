package com.stup.wristbandprinter.security;

import com.stup.wristbandprinter.config.AdminProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

@Profile("!worker")
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
