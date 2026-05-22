package com.stup.wristbandprinter.exception;

public class LabelaryUnavailableException extends RuntimeException {
    public LabelaryUnavailableException(String message) { super(message); }
    public LabelaryUnavailableException(String message, Throwable cause) { super(message, cause); }
}
