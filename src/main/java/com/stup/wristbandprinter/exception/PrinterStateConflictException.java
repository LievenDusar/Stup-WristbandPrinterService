package com.stup.wristbandprinter.exception;

/** An admin op is invalid for the printer's current state (e.g. hide an online printer,
 *  set a hidden printer as default). */
public class PrinterStateConflictException extends RuntimeException {
    public PrinterStateConflictException(String message) {
        super(message);
    }
}
