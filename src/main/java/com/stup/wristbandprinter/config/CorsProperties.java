package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS allow-list for browser callers (e.g. the STUP Symfony app calling the print/preview
 * endpoints directly with the print-only API key). Empty by default, which means no cross-origin
 * request is allowed — same-origin traffic is unaffected. Set {@code cors.allowed-origins} (env var
 * {@code CORS_ALLOWED_ORIGINS}, comma-separated) to the exact origin(s) that may call from a browser.
 */
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /** Exact origins permitted to make cross-origin browser requests, e.g. https://app.stup.be. */
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
