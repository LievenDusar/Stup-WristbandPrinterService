package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public class PrintJob {

    private final UUID jobId;
    private final WristbandPrintRequest request;
    private final String printerId;
    private final String printerName;
    private PrintJobStatus status;
    private final Instant submittedAt;
    private Instant completedAt;
    private String error;

    public PrintJob(UUID jobId, WristbandPrintRequest request) {
        this(jobId, request, null, null);
    }

    public PrintJob(UUID jobId, WristbandPrintRequest request, String printerId, String printerName) {
        this.jobId = jobId;
        this.request = request;
        this.printerId = printerId;
        this.printerName = printerName;
        this.status = PrintJobStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    private PrintJob(UUID jobId, WristbandPrintRequest request, String printerId, String printerName,
                     PrintJobStatus status, Instant submittedAt, Instant completedAt, String error) {
        this.jobId = jobId;
        this.request = request;
        this.printerId = printerId;
        this.printerName = printerName;
        this.status = status;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.error = error;
    }

    /** Rebuild a job from durable storage (no printer recorded). */
    public static PrintJob restore(UUID jobId, WristbandPrintRequest request, PrintJobStatus status,
                                   Instant submittedAt, Instant completedAt, String error) {
        return restore(jobId, request, null, null, status, submittedAt, completedAt, error);
    }

    /** Rebuild a job from durable storage, preserving its printer and original state. */
    public static PrintJob restore(UUID jobId, WristbandPrintRequest request, String printerId,
                                   String printerName, PrintJobStatus status, Instant submittedAt,
                                   Instant completedAt, String error) {
        return new PrintJob(jobId, request, printerId, printerName, status, submittedAt, completedAt, error);
    }

    public UUID getJobId() { return jobId; }
    public WristbandPrintRequest getRequest() { return request; }
    public String getPrinterId() { return printerId; }
    public String getPrinterName() { return printerName; }

    public synchronized PrintJobStatus getStatus() { return status; }
    public synchronized void setStatus(PrintJobStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }

    public synchronized Instant getCompletedAt() { return completedAt; }
    public synchronized void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public synchronized String getError() { return error; }
    public synchronized void setError(String error) { this.error = error; }

    /**
     * Atomically update status, error, and completedAt in one synchronized call.
     * Use this instead of individual setters when transitioning to a terminal state
     * to prevent readers seeing a partially-updated snapshot.
     */
    public synchronized void complete(PrintJobStatus status, String error, Instant completedAt) {
        this.status = status;
        this.error = error;
        this.completedAt = completedAt;
    }

    public synchronized PrintJobResponse toResponse() {
        return new PrintJobResponse(
            jobId, status, printerId, printerName,
            request.getEventName(), request.getFirstName(), request.getLastName(),
            submittedAt, completedAt, error);
    }

    public synchronized PrintJobDetailResponse toDetailResponse() {
        return new PrintJobDetailResponse(
            jobId, status, printerId, printerName,
            request.getEventName(), request.getFirstName(), request.getLastName(),
            request.getAssociationName(), request.getBarcodeValue(),
            submittedAt, completedAt, error);
    }
}
