package com.stup.wristbandprinter.exception;

public class PrinterUnavailableException extends RuntimeException {
    public PrinterUnavailableException(String message) { super(message); }
    public PrinterUnavailableException(String message, Throwable cause) { super(message, cause); }
}
