package com.stup.wristbandprinter.exception;

public class LogoNotFoundException extends RuntimeException {
    public LogoNotFoundException(String message) { super(message); }
    public LogoNotFoundException(String message, Throwable cause) { super(message, cause); }
}
