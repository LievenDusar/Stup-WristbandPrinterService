package com.stup.wristbandprinter.domain;

public record WristbandData(
    String eventName,
    String firstName,
    String lastName,
    String associationName,
    String barcodeValue
) {}
