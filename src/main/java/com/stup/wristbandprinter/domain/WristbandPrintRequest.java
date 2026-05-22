package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Data required to print or preview a wristband")
public class WristbandPrintRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "firstName must not be blank")
    @Schema(example = "Jan")
    private String firstName;

    @NotBlank(message = "lastName must not be blank")
    @Schema(example = "Janssens")
    private String lastName;

    @NotBlank(message = "associationName must not be blank")
    @Schema(example = "STUP vzw")
    private String associationName;

    @NotBlank(message = "barcodeValue must not be blank")
    @Schema(example = "123456789")
    private String barcodeValue;

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
}
