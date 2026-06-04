package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobDetailResponse(
    UUID jobId,
    PrintJobStatus status,
    String printerId,
    String printerName,
    String eventName,
    String firstName,
    String lastName,
    String associationName,
    String barcodeValue,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
