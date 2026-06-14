package com.stup.wristbandprinter.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of PATCH /api/wristbands/printers/{id} — operator rename. */
public record RenamePrinterRequest(@NotBlank String displayName) {}
