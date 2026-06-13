package com.stup.wristbandprinter.cluster.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/internal/printers/register — a worker announcing itself. */
public record RegisterPrinterRequest(
    @NotBlank String id,
    @NotBlank String displayName,
    @NotBlank String baseUrl) {
}
