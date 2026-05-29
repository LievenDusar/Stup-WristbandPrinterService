package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_jobs")
public class PrintJobEntity {

    @Id
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    private PrintJobStatus status;

    private String eventName;
    private String firstName;
    private String lastName;
    private String associationName;
    private String barcodeValue;

    private Instant submittedAt;
    private Instant completedAt;

    @Column(length = 2000)
    private String error;

    protected PrintJobEntity() {
    }

    public PrintJobEntity(UUID jobId, PrintJobStatus status, String eventName, String firstName,
                          String lastName, String associationName, String barcodeValue,
                          Instant submittedAt, Instant completedAt, String error) {
        this.jobId = jobId;
        this.status = status;
        this.eventName = eventName;
        this.firstName = firstName;
        this.lastName = lastName;
        this.associationName = associationName;
        this.barcodeValue = barcodeValue;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.error = error;
    }

    public UUID getJobId() { return jobId; }
    public PrintJobStatus getStatus() { return status; }
    public String getEventName() { return eventName; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getAssociationName() { return associationName; }
    public String getBarcodeValue() { return barcodeValue; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getError() { return error; }
}
