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

    /** Number of physical copies to print (^PQ). Always >= 1; null on the DTO means default 1. */
    int getCopies();

    /** Return a copy of this request with the copies count overridden. */
    PrintableRequest withCopies(int copies);

    /** Return a copy of this request with the printerId overridden to the resolved printer id. */
    PrintableRequest withPrinterId(String printerId);
}
