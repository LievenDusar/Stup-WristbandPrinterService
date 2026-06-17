package com.stup.wristbandprinter.editor.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * One element on the wristband. Coordinates/sizes are in printer dots; {@code rotation} is one of
 * 0/90/180/270. A {@code GROUP} carries {@code children} plus layout fields and ignores its own
 * leaf fields; its children's {@code x}/{@code y} are computed by the layout. Leaf fields not
 * relevant to a type are null.
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
    String font,                // TEXT, STATIC_TEXT
    String symbology,           // BARCODE
    Boolean showHumanReadable,  // BARCODE
    UUID assetId,               // IMAGE
    ShapeType shape,            // SHAPE
    Integer thicknessDots,      // SHAPE
    List<TemplateElement> children,  // GROUP
    StackDirection stackDirection,   // GROUP
    Integer marginDots,              // GROUP
    CrossAlign crossAlign,           // GROUP
    String sampleText,               // TEXT / STATIC_TEXT (design + preview only)
    Boolean centerOnBand             // any leaf/group: renderer centers across band width
) {

    /** Backwards-compatible 16-arg leaf constructor (group/sample fields default to null). */
    public TemplateElement(String id, ElementType type, int x, int y, int widthDots, int heightDots,
                           int rotation, DataBinding binding, String value, Integer fontSize, String font,
                           String symbology, Boolean showHumanReadable, UUID assetId, ShapeType shape,
                           Integer thicknessDots) {
        this(id, type, x, y, widthDots, heightDots, rotation, binding, value, fontSize, font,
            symbology, showHumanReadable, assetId, shape, thicknessDots,
            null, null, null, null, null, null);
    }

    /** Factory for a group element. Leaf fields are null; {@code x}/{@code y} are the group origin. */
    public static TemplateElement group(String id, int x, int y, StackDirection stackDirection,
                                        int marginDots, CrossAlign crossAlign,
                                        List<TemplateElement> children) {
        return new TemplateElement(id, ElementType.GROUP, x, y, 0, 0, 0,
            null, null, null, null, null, null, null, null, null,
            children, stackDirection, marginDots, crossAlign, null, null);
    }

    /** Returns a copy with centerOnBand set. */
    public TemplateElement withCenterOnBand(boolean v) {
        return new TemplateElement(id, type, x, y, widthDots, heightDots, rotation, binding, value,
            fontSize, font, symbology, showHumanReadable, assetId, shape, thicknessDots,
            children, stackDirection, marginDots, crossAlign, sampleText, v);
    }
}
