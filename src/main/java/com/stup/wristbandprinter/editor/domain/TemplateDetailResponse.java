package com.stup.wristbandprinter.editor.domain;

import java.time.Instant;
import java.util.UUID;

/** Full template, including the element model and the saved ZPL snapshot (null until Plan 2). */
public record TemplateDetailResponse(
    UUID id,
    String slug,
    String name,
    String projectType,
    String defaultPreviewColor,
    TemplateDefinition definition,
    String generatedZpl,
    Instant updatedAt
) {
}
