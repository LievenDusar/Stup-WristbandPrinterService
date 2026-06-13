package com.stup.wristbandprinter.domain;

/**
 * Resolved layout data for a permit wristband.
 * Assembled from {@link PermitWristbandPrintRequest} by {@link com.stup.wristbandprinter.service.PermitLayoutService}.
 */
public record PermitWristbandData(
    String eventName,
    String permitLabel,
    String clubName,  // null or blank → dotted fill-in line
    String codeValue,        // null → no scan-code block rendered
    CodeSymbology symbology,
    String stockColorHex     // "#FFFFFF" when absent; resolved from stockColorCode at build time
) {}
