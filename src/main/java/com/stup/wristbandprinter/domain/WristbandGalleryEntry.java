package com.stup.wristbandprinter.domain;

/**
 * Describes a wristband type in the gallery.
 *
 * @param wristbandType  discriminator (CREW / PERMIT)
 * @param displayName    human-friendly name shown in the gallery tile
 * @param description    one-line description of the band's purpose
 * @param previewUrl     URL to POST to for a PNG preview (relative, no host)
 * @param samplePayload  JSON string with sample data for the preview call
 */
public record WristbandGalleryEntry(
    WristbandType wristbandType,
    String displayName,
    String description,
    String previewUrl,
    String samplePayload
) {}
