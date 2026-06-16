package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(value = "wristbandType", allowGetters = true)
@Schema(description = "Data required to print or preview a wristband")
public final class WristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "firstName must not be blank")
    @Schema(example = "Annechien")
    private String firstName;

    @NotBlank(message = "lastName must not be blank")
    @Schema(example = "Van De Wall")
    private String lastName;

    @NotBlank(message = "clubName must not be blank")
    @Schema(example = "Chiro Sint-Christina Brustem")
    private String clubName;

    @NotBlank(message = "barcodeValue must not be blank")
    @Schema(example = "12345654245524789")
    private String barcodeValue;

    @Schema(description = "Optional template id; when set the wristband is rendered from that template instead of the default layout")
    private java.util.UUID templateId;

    @Schema(description = "Optional scan-code symbology; defaults to CODE128 when omitted")
    private CodeSymbology codeSymbology;

    @Schema(description = "Optional stock-color code (1 = white). Preview-only tint.")
    private Integer stockColorCode;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    @Schema(description = "Number of copies to print; defaults to 1 when omitted", example = "1")
    @Min(value = 1, message = "copies must be at least 1")
    private Integer copies;

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }

    public String getBarcodeValue() { return barcodeValue; }
    public void setBarcodeValue(String barcodeValue) { this.barcodeValue = barcodeValue; }

    public java.util.UUID getTemplateId() { return templateId; }
    public void setTemplateId(java.util.UUID templateId) { this.templateId = templateId; }

    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public void setCodeSymbology(CodeSymbology codeSymbology) { this.codeSymbology = codeSymbology; }

    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    @Override
    public int getCopies() { return copies == null ? 1 : copies; }
    public void setCopies(Integer copies) { this.copies = copies; }

    @Override
    public WristbandType getWristbandType() { return WristbandType.CREW; }

    @Override
    public PrintableRequest withPrinterId(String printerId) {
        WristbandPrintRequest copy = new WristbandPrintRequest();
        copy.eventName      = this.eventName;
        copy.firstName      = this.firstName;
        copy.lastName       = this.lastName;
        copy.clubName       = this.clubName;
        copy.barcodeValue   = this.barcodeValue;
        copy.templateId     = this.templateId;
        copy.codeSymbology  = this.codeSymbology;
        copy.stockColorCode = this.stockColorCode;
        copy.printerId      = printerId;
        copy.copies         = this.copies;
        return copy;
    }

    @Override
    public PrintableRequest withCopies(int copies) {
        WristbandPrintRequest copy = (WristbandPrintRequest) withPrinterId(this.printerId);
        copy.copies = copies;
        return copy;
    }
}
