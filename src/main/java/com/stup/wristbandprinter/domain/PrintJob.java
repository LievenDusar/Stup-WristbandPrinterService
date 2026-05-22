package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public class PrintJob {

    private final UUID jobId;
    private final WristbandPrintRequest request;
    private volatile PrintJobStatus status;
    private final Instant submittedAt;
    private volatile Instant completedAt;
    private volatile String error;

    public PrintJob(UUID jobId, WristbandPrintRequest request) {
        this.jobId = jobId;
        this.request = request;
        this.status = PrintJobStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    public UUID getJobId() { return jobId; }
    public WristbandPrintRequest getRequest() { return request; }

    public PrintJobStatus getStatus() { return status; }
    public void setStatus(PrintJobStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public PrintJobResponse toResponse() {
        return new PrintJobResponse(
            jobId,
            status,
            request.getEventName(),
            submittedAt,
            completedAt,
            error
        );
    }
}
