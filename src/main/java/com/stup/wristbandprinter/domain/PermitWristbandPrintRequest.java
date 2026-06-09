package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for printing a permit wristband (campsite resource access: electricity, parking, …).
 *
 * <p>Layout: STUP logo → "Toelating [permitLabel]" header + writing line → optional scan code
 * → eventName + event logo.</p>
 */
@Schema(description = "Data required to print or preview a permit wristband")
public final class PermitWristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "permitLabel must not be blank")
    @Schema(example = "Elektriciteit")
    private String permitLabel;

    @Schema(description = "Font-Awesome icon name — stored for future rendering; not printed now", example = "bolt")
    private String iconName;

    @Schema(description = "Optional scan-code value; when present a barcode is printed")
    private String codeValue;

    @Schema(description = "Scan-code symbology; defaults to CODE128 when omitted")
    private CodeSymbology codeSymbology;

    @Schema(description = "Optional stock-color code (1 = white). Preview-only tint.")
    private Integer stockColorCode;

    @Schema(description = "Optional id of the printer to use; when omitted the default printer is used")
    private String printerId;

    @Override
    public String getPrinterId() { return printerId; }
    public void setPrinterId(String printerId) { this.printerId = printerId; }

    @Override
    public WristbandType getWristbandType() { return WristbandType.PERMIT; }

    @Override
    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    @Override
    public PrintableRequest withPrinterId(String printerId) {
        PermitWristbandPrintRequest copy = new PermitWristbandPrintRequest();
        copy.eventName     = this.eventName;
        copy.permitLabel   = this.permitLabel;
        copy.iconName      = this.iconName;
        copy.codeValue     = this.codeValue;
        copy.codeSymbology = this.codeSymbology;
        copy.stockColorCode = this.stockColorCode;
        copy.printerId     = printerId;
        return copy;
    }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getPermitLabel() { return permitLabel; }
    public void setPermitLabel(String permitLabel) { this.permitLabel = permitLabel; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public String getCodeValue() { return codeValue; }
    public void setCodeValue(String codeValue) { this.codeValue = codeValue; }

    public CodeSymbology getCodeSymbology() { return codeSymbology; }
    public void setCodeSymbology(CodeSymbology codeSymbology) { this.codeSymbology = codeSymbology; }
}
