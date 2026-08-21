package com.stup.wristbandprinter.domain;

/**
 * Resolved layout data for a free-text wristband.
 * Assembled from {@link FreeTextWristbandPrintRequest} by {@link com.stup.wristbandprinter.service.FreeTextLayoutService}.
 */
public record FreeTextWristbandData(
    String text,
    String stockColorHex     // "#FFFFFF" when absent; resolved from stockColorCode at build time
) {}
