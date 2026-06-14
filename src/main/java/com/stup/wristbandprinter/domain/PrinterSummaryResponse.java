package com.stup.wristbandprinter.domain;

import java.time.Instant;

/** A printer as exposed to the UI (no internal base URL). */
public record PrinterSummaryResponse(String id, String displayName, boolean online,
                                     boolean hidden, boolean isDefault, Instant lastSeenAt) {}
