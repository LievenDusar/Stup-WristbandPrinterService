package com.stup.wristbandprinter.exception;

/** Thrown when a print request targets a printer id that is not in the registry. */
public class UnknownPrinterException extends RuntimeException {
    public UnknownPrinterException(String message) {
        super(message);
    }
}
