package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for printing a free-text wristband: freely entered text between two STUP logos.
 *
 * <p>Layout: STUP logo → free text → STUP logo, all centered along both band axes.</p>
 */
// Polymorphism is declared on PrintableRequest. Here the discriminator is redundant: NONE suppresses
// the type-info wrapper, and @JsonIgnoreProperties drops wristbandType on deserialize (there is no
// setter) while allowGetters=true keeps getWristbandType() in serialized output.
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(value = "wristbandType", allowGetters = true)
@Schema(description = "Data required to print or preview a free-text wristband")
public final class FreeTextWristbandPrintRequest implements PrintableRequest {

    @NotBlank(message = "text must not be blank")
    @Schema(example = "Backstage")
    private String text;

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
    public WristbandType getWristbandType() { return WristbandType.FREETEXT; }

    @Override
    public Integer getStockColorCode() { return stockColorCode; }
    public void setStockColorCode(Integer stockColorCode) { this.stockColorCode = stockColorCode; }

    @Override
    public int getCopies() { return copies == null ? 1 : copies; }
    public void setCopies(Integer copies) { this.copies = copies; }

    @Override
    public PrintableRequest withPrinterId(String printerId) {
        FreeTextWristbandPrintRequest copy = new FreeTextWristbandPrintRequest();
        copy.text           = this.text;
        copy.stockColorCode = this.stockColorCode;
        copy.printerId       = printerId;
        copy.copies          = this.copies;
        return copy;
    }

    @Override
    public PrintableRequest withCopies(int copies) {
        FreeTextWristbandPrintRequest copy = (FreeTextWristbandPrintRequest) withPrinterId(this.printerId);
        copy.copies = copies;
        return copy;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
