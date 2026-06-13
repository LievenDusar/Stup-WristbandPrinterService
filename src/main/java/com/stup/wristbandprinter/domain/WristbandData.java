package com.stup.wristbandprinter.domain;

public record WristbandData(
    String eventName,
    String firstName,
    String lastName,
    String clubName,
    String barcodeValue,
    CodeSymbology codeSymbology
) {
    /** Backward-compatible constructor; defaults to CODE128. */
    public WristbandData(String eventName, String firstName, String lastName,
                         String clubName, String barcodeValue) {
        this(eventName, firstName, lastName, clubName, barcodeValue, CodeSymbology.CODE128);
    }
}
