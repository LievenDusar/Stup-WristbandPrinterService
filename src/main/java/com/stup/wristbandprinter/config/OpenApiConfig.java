package com.stup.wristbandprinter.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Central OpenAPI / Swagger metadata for the management role.
 *
 * <p>Declares the tag set and its display order so the Swagger UI groups endpoints as:
 * <strong>Wristbands</strong> (polymorphic print/preview, type selected by {@code wristbandType})
 * → <strong>Jobs</strong> → <strong>Printers &amp; Gallery</strong> →
 * <strong>Templates</strong> → <strong>Authentication</strong>.</p>
 *
 * <p>Operations are assigned to these tags via {@code @Operation(tags = …)} on each controller
 * method (one tag per operation). The single {@code POST /api/wristbands/print} endpoint (and its
 * preview siblings) accept both crew and permit requests via the {@code wristbandType} discriminator
 * field, while the type-agnostic job/printer endpoints get their own sections.</p>
 *
 * <p>This definition is additive — springdoc merges it with the auto-generated spec and the
 * {@code @SecurityScheme} declared on {@link SecurityConfig}.</p>
 */
@Profile("!worker")
@Configuration
@OpenAPIDefinition(
    info = @Info(title = "STUP Wristband Printer Service API", version = "v1"),
    tags = {
        @Tag(name = "Wristbands",
             description = "Print and preview wristbands (crew or permit, chosen by the wristbandType field)"),
        @Tag(name = "Jobs",
             description = "Manage print jobs: list, detail, preview, live status (SSE), reprint, cancel, clear"),
        @Tag(name = "Printers & Gallery",
             description = "List routable printers and browse the wristband gallery"),
        @Tag(name = "Templates",
             description = "Create, manage and browse wristband templates"),
        @Tag(name = "Authentication",
             description = "Admin login for the jobs page")
    }
)
public class OpenApiConfig {
}
