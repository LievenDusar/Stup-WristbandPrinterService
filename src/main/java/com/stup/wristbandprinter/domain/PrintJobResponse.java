package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    WristbandType wristbandType,
    String printerId,
    String printerName,
    String eventName,
    String firstName,   // null for PERMIT bands
    String lastName,    // null for PERMIT bands
    String permitLabel, // null for CREW bands
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
