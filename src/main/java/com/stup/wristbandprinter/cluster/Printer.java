package com.stup.wristbandprinter.cluster;

/** A printer in the registry: a stable id, a human label, and the worker's internal base URL. */
public record Printer(String id, String displayName, String baseUrl) {}
