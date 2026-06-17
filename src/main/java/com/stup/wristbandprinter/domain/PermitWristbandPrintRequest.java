package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for printing a permit wristband (campsite resource access: electricity, parking, …).
 *
 * <p>Layout: STUP logo → "Toelating [permitLabel]" header + writing line → optional scan code
 * → eventName + event logo.</p>
 */
// Polymorphism is declared on PrintableRequest. Here the discriminator is redundant: NONE suppresses
// the type-info wrapper, and @JsonIgnoreProperties drops wristbandType on deserialize (there is no
// setter) while allowGetters=true keeps getWristbandType() in serialized output.
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(value = "wristbandType", allowGetters = true)
@Schema(description = "Data required to print or preview a permit wristband")
public final class PermitWristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "permitLabel must not be blank")
    @Schema(example = "Elektriciteit")
    private String permitLabel;

    @Schema(description = "Optional club name; when absent a dotted fill-in line is printed instead")
    private String clubName;

    @Schema(description = "Font-Awesome icon name — stored for future rendering; not printed now", example = "bolt")
    private String iconName;

    @Schema(description = "Optional scan-code value; when present a barcode is printed")
    private String codeValue;

    @Schema(description = "Scan-code symbology; defaults to CODE128 when omitted")
    private CodeSymbology codeSymbology;

    @Schema(description = "Optional stock-color code (configured: 1=white, 2=purple, 3=yellow, 4=blue, 5=green, 6=red). Preview-only tint.", example = "1")
    private Integer stockColorCode;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    @Schema(description = "Number of copies to print; defaults to 1 when omitted", example = "1")
    @Min(value = 1, message = "copies must be at least 1")
    private Integer copies;

    @Override
    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    @Override
    public WristbandType getWristbandType() { return WristbandType.PERMIT; }

    @Override
    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    @Override
    public int getCopies() { return copies == null ? 1 : copies; }
    public void setCopies(Integer copies) { this.copies = copies; }

    @Override
    public PrintableRequest withPrinterId(String printerId) {
        PermitWristbandPrintRequest copy = new PermitWristbandPrintRequest();
        copy.eventName       = this.eventName;
        copy.permitLabel     = this.permitLabel;
        copy.clubName        = this.clubName;
        copy.iconName        = this.iconName;
        copy.codeValue       = this.codeValue;
        copy.codeSymbology   = this.codeSymbology;
        copy.stockColorCode  = this.stockColorCode;
        copy.printerId       = printerId;
        copy.copies          = this.copies;
        return copy;
    }

    @Override
    public PrintableRequest withCopies(int copies) {
        PermitWristbandPrintRequest copy = (PermitWristbandPrintRequest) withPrinterId(this.printerId);
        copy.copies = copies;
        return copy;
    }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getPermitLabel() { return permitLabel; }
    public void setPermitLabel(String permitLabel) { this.permitLabel = permitLabel; }

    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public String getCodeValue() { return codeValue; }
    public void setCodeValue(String codeValue) { this.codeValue = codeValue; }

    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public void setCodeSymbology(CodeSymbology codeSymbology) { this.codeSymbology = codeSymbology; }
}
