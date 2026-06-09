package com.stup.wristbandprinter.domain;

/**
 * Sealed interface for all wristband print request types.
 * Permits: {@link WristbandPrintRequest} (CREW), {@link PermitWristbandPrintRequest} (PERMIT).
 */
public sealed interface PrintableRequest permits WristbandPrintRequest, PermitWristbandPrintRequest {

    String getPrinterId();

    WristbandType getWristbandType();

    /** Optional stock-color code (1 = white, default). Preview-only tint; ZPL is always monochrome. */
    Integer getStockColorCode();

    /** Return a copy of this request with the printerId overridden to the resolved printer id. */
    PrintableRequest withPrinterId(String printerId);
}
