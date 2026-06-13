package com.stup.wristbandprinter.exception;

/** Thrown when a print is requested but no printer is registered/eligible to serve it. */
public class NoPrintersAvailableException extends RuntimeException {
    public NoPrintersAvailableException(String message) {
        super(message);
    }
}
