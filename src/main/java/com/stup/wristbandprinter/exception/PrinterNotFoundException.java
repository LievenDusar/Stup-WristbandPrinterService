package com.stup.wristbandprinter.exception;

/** An admin op referenced a printer id that does not exist. */
public class PrinterNotFoundException extends RuntimeException {
    public PrinterNotFoundException(String message) {
        super(message);
    }
}
