package com.stup.wristbandprinter.domain;

/** A printer as exposed to UI/external callers (no internal base URL). */
public record PrinterSummaryResponse(String id, String displayName) {}
