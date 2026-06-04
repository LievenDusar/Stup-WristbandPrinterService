package com.stup.wristbandprinter.cluster.dto;

import java.util.UUID;

/** Payload sent from the management service to a printer-worker to print one job. */
public record PrintForwardRequest(UUID jobId, String zpl) {}
