package com.stup.wristbandprinter.editor.domain;

import java.time.Instant;
import java.util.UUID;

/** Catalog list item — what Symfony fetches to show the template picker. */
public record TemplateSummaryResponse(
    UUID id,
    String slug,
    String name,
    String projectType,
    Instant updatedAt
) {
}
