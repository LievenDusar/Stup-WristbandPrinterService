package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Data required to print or preview a wristband")
public class WristbandPrintRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "firstName must not be blank")
    @Schema(example = "Annechien")
    private String firstName;

    @NotBlank(message = "lastName must not be blank")
    @Schema(example = "Van De Wall")
    private String lastName;

    @NotBlank(message = "associationName must not be blank")
    @Schema(example = "Chiro Sint-Christina Brustem")
    private String associationName;

    @NotBlank(message = "barcodeValue must not be blank")
    @Schema(example = "12345654245524789")
    private String barcodeValue;

    @Schema(description = "Optional template id; when set the wristband is rendered from that template instead of the default layout")
    private java.util.UUID templateId;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAssociationName() { return associationName; }
    public void setAssociationName(String associationName) { this.associationName = associationName; }

    public String getBarcodeValue() { return barcodeValue; }
    public void setBarcodeValue(String barcodeValue) { this.barcodeValue = barcodeValue; }

    public java.util.UUID getTemplateId() { return templateId; }
    public void setTemplateId(java.util.UUID templateId) { this.templateId = templateId; }

    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }
}
