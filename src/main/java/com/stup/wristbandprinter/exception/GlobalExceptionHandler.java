package com.stup.wristbandprinter.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                (a, b) -> a));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("error", "Validation failed");
        body.put("message", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(PrinterUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handlePrinterUnavailable(PrinterUnavailableException ex) {
        log.warn("Printer unavailable: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Printer unavailable", ex.getMessage());
    }

    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<Map<String, Object>> handleQueueFull(QueueFullException ex) {
        log.warn("Print queue full: {}", ex.getMessage());
        return errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Queue full", ex.getMessage());
    }

    @ExceptionHandler(JobNotCancellableException.class)
    public ResponseEntity<Map<String, Object>> handleNotCancellable(JobNotCancellableException ex) {
        log.warn("Job not cancellable: {}", ex.getMessage());
        return errorResponse(HttpStatus.CONFLICT, "Job not cancellable", ex.getMessage());
    }

    @ExceptionHandler(LabelaryUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLabelaryUnavailable(LabelaryUnavailableException ex) {
        log.warn("Labelary unavailable: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Labelary unavailable", ex.getMessage());
    }

    @ExceptionHandler(LogoNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleLogoNotFound(LogoNotFoundException ex) {
        log.error("Logo not available: {}", ex.getMessage());
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Logo not available", "Logo image could not be loaded — check server configuration");
    }

    @ExceptionHandler(UnknownPrinterException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownPrinter(UnknownPrinterException ex) {
        log.warn("Unknown printer: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Unknown printer", ex.getMessage());
    }

    @ExceptionHandler(InvalidStockColorException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStockColor(InvalidStockColorException ex) {
        log.warn("Invalid stock color: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid stock color", ex.getMessage());
    }

    @ExceptionHandler(InvalidCopiesException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCopies(InvalidCopiesException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid copies", ex.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not allowed: {}", ex.getMessage());
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
