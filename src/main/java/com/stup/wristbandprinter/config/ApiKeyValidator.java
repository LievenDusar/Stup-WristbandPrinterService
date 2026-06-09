package com.stup.wristbandprinter.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyValidator implements InitializingBean {

    private final Environment environment;
    private final String apiKey;
    private final AdminProperties adminProperties;
    private final String cookieSecret;

    public ApiKeyValidator(Environment environment,
                           @Value("${security.api-key:}") String apiKey,
                           AdminProperties adminProperties,
                           @Value("${security.cookie-secret:}") String cookieSecret) {
        this.environment = environment;
        this.apiKey = apiKey;
        this.adminProperties = adminProperties;
        this.cookieSecret = cookieSecret;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        boolean worker = environment.acceptsProfiles(Profiles.of("worker"));
        validate(prod, apiKey);
        // Printer-workers have no admin UI — they never need an admin password or cookie secret.
        if (!worker) {
            validateAdminPassword(prod, adminProperties.getPassword());
            validateCookieSecret(prod, cookieSecret);
        }
    }

    static void validate(boolean prodProfileActive, String apiKey) {
        if (!prodProfileActive) {
            return;
        }
        if (apiKey == null
            || apiKey.isBlank()
            || apiKey.equals("changeme")
            || apiKey.equals("${SECURITY_API_KEY}")) {
            throw new IllegalStateException(
                "security.api-key must be set to a non-default value when running with the "
                    + "'prod' profile. Set the SECURITY_API_KEY environment variable.");
        }
    }

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

    static void validateCookieSecret(boolean prodProfileActive, String cookieSecret) {
        if (!prodProfileActive) {
            return;
        }
        if (cookieSecret == null || cookieSecret.isBlank()) {
            throw new IllegalStateException(
                "security.cookie-secret must be set when running with the 'prod' profile. "
                    + "Set the COOKIE_SECRET environment variable.");
        }
    }
}
