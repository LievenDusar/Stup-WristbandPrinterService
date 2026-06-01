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

    public ApiKeyValidator(Environment environment,
                           @Value("${security.api-key:}") String apiKey,
                           AdminProperties adminProperties) {
        this.environment = environment;
        this.apiKey = apiKey;
        this.adminProperties = adminProperties;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        validate(prod, apiKey);
        validateAdminPassword(prod, adminProperties.getPassword());
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
}
