package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    String eventName,
    String firstName,
    String lastName,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
