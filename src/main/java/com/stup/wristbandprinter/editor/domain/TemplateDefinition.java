package com.stup.wristbandprinter.editor.domain;

import java.util.List;

/** The full declarative description of a wristband layout. Stored as jsonb. */
public record TemplateDefinition(Canvas canvas, List<TemplateElement> elements) {
}
