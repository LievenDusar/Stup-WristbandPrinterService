package com.stup.wristbandprinter.editor.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One positioned element on the wristband. All coordinates and sizes are in printer dots.
 * {@code rotation} is one of 0, 90, 180, 270 (the only orientations ZPL supports).
 * Fields not relevant to a given {@link ElementType} are null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateElement(
    String id,
    ElementType type,
    int x,
    int y,
    int widthDots,
    int heightDots,
    int rotation,
    DataBinding binding,        // TEXT, BARCODE
    String value,               // STATIC_TEXT
    Integer fontSize,           // TEXT, STATIC_TEXT
    String font,                // TEXT, STATIC_TEXT (ZPL font id, e.g. "0")
    String symbology,           // BARCODE (e.g. CODE128)
    Boolean showHumanReadable,  // BARCODE
    UUID assetId,               // IMAGE
    ShapeType shape,            // SHAPE
    Integer thicknessDots       // SHAPE
) {
}
