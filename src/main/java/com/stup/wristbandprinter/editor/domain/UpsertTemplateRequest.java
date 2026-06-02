package com.stup.wristbandprinter.editor.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Create or update a wristband template")
public record UpsertTemplateRequest(
    @NotBlank(message = "name must not be blank")
    @Schema(example = "Festival Band")
    String name,

    @Schema(example = "festival", description = "Optional, non-unique grouping tag")
    String projectType,

    @Schema(example = "white", description = "Default preview background colour")
    String defaultPreviewColor,

    @NotNull(message = "definition must not be null")
    TemplateDefinition definition
) {
}
