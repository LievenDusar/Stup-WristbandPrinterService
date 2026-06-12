package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandType;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "wristband_type", nullable = false)
    private WristbandType wristbandType;

    private String printerId;
    private String printerName;

    // ── CREW fields ──────────────────────────────────────────────────────────
    private String eventName;
    private String firstName;
    private String lastName;
    private String associationName;
    private String barcodeValue;

    // ── PERMIT fields ─────────────────────────────────────────────────────────
    private String permitLabel;
    private String iconName;

    // ── Shared optional fields ────────────────────────────────────────────────
    private Integer stockColorCode;
    private String  codeValue;

    @Enumerated(EnumType.STRING)
    private CodeSymbology codeSymbology;

    @Column(nullable = false)
    private Integer copies;

    private Instant submittedAt;
    private Instant completedAt;

    @Column(length = 2000)
    private String error;

    private boolean deleted;

    protected PrintJobEntity() {
    }

    public PrintJobEntity(UUID jobId, PrintJobStatus status, WristbandType wristbandType,
                          String printerId, String printerName,
                          String eventName, String firstName, String lastName,
                          String associationName, String barcodeValue,
                          String permitLabel, String iconName,
                          Integer stockColorCode, String codeValue, CodeSymbology codeSymbology,
                          Integer copies,
                          Instant submittedAt, Instant completedAt, String error) {
        this.jobId          = jobId;
        this.status         = status;
        this.wristbandType  = wristbandType;
        this.printerId      = printerId;
        this.printerName    = printerName;
        this.eventName      = eventName;
        this.firstName      = firstName;
        this.lastName       = lastName;
        this.associationName = associationName;
        this.barcodeValue   = barcodeValue;
        this.permitLabel    = permitLabel;
        this.iconName       = iconName;
        this.stockColorCode = stockColorCode;
        this.codeValue      = codeValue;
        this.codeSymbology  = codeSymbology;
        this.copies         = copies;
        this.submittedAt    = submittedAt;
        this.completedAt    = completedAt;
        this.error          = error;
    }

    public UUID getJobId()           { return jobId; }
    public PrintJobStatus getStatus(){ return status; }
    public WristbandType getWristbandType() { return wristbandType; }
    public String getPrinterId()     { return printerId; }
    public String getPrinterName()   { return printerName; }
    public String getEventName()     { return eventName; }
    public String getFirstName()     { return firstName; }
    public String getLastName()      { return lastName; }
    public String getAssociationName(){ return associationName; }
    public String getBarcodeValue()  { return barcodeValue; }
    public String getPermitLabel()   { return permitLabel; }
    public String getIconName()      { return iconName; }
    public Integer getStockColorCode(){ return stockColorCode; }
    public String  getCodeValue()    { return codeValue; }
    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public int getCopies()           { return copies == null ? 1 : copies; }
    public Instant getSubmittedAt()  { return submittedAt; }
    public Instant getCompletedAt()  { return completedAt; }
    public String getError()         { return error; }
    public boolean isDeleted()       { return deleted; }
}
