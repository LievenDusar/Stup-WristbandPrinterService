package com.stup.wristbandprinter.cluster.dto;

import java.time.Instant;

/** Payload of the named "printer" SSE event (D6): a printer's current public state. */
public record PrinterEvent(String id, String displayName, boolean online, boolean hidden,
                           boolean isDefault, Instant lastSeenAt) {
}
