package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;

/**
 * Static utility for generating ZPL scan-code commands and estimating their
 * physical size in dots. Supports CODE128 (rotated bottom-up ^BCB), CODE39
 * (rotated bottom-up ^B3B), and QR (normal orientation ^BQN).
 *
 * <p>All rotated barcodes (CODE128 / CODE39) are printed 90° CCW (bottom-up):
 * their "height" maps to the X axis (across the band), and the barcode body
 * grows in the +Y direction (along the band length). QR is printed in normal
 * orientation; both dimensions are square.</p>
 */
public final class ScanCodeRenderer {

    // Code 128: fixed modules = start(11) + check(11) + stop(13) = 35
    static final int CODE128_FIXED_INK_MODULES = 35;
    static final int CODE128_DATA_MODULES_PER_CHAR = 11;

    // Code 39: each character = 15 narrow-bar modules; start + stop = 2 extra chars
    static final int CODE39_CHAR_MODULES = 15;

    // Quiet zone shared by CODE128 and CODE39 (blank modules on each side)
    static final int BARCODE_QUIET_MODULES = 20;

    // QR caps
    private static final int QR_MAX_MAGNIFICATION = 10;
    private static final int QR_MIN_MAGNIFICATION = 2;

    private ScanCodeRenderer() {}

    /**
     * Appends the ZPL commands for a scan code at position {@code (x, y)}.
     *
     * @param zpl             target buffer
     * @param value           raw data (will be sanitized of ^ and ~)
     * @param symbology       CODE128, CODE39, or QR
     * @param x               field origin X
     * @param y               field origin Y (top of scan-code block along band)
     * @param heightDots      bar height for rotated codes; approximate side length for QR
     * @param moduleWidthDots narrow-bar width (^BY param); ignored for QR
     * @param showHumanReadable whether to print the human-readable line (ignored for QR)
     */
    public static void appendTo(StringBuilder zpl, String value, CodeSymbology symbology,
                                  int x, int y, int heightDots, int moduleWidthDots,
                                  boolean showHumanReadable) {
        switch (symbology) {
            case CODE128 -> appendCode128(zpl, sanitize(value), x, y, heightDots, moduleWidthDots, showHumanReadable);
            case CODE39  -> appendCode39(zpl,  sanitize(value), x, y, heightDots, moduleWidthDots, showHumanReadable);
            case QR      -> appendQr(zpl,      sanitize(value), x, y, heightDots);
        }
    }

    /**
     * Estimates the Y-axis footprint (along the band length) of a scan code in dots.
     * Used for vertical layout math.
     */
    public static int estimateYLength(String value, CodeSymbology symbology,
                                       int moduleWidthDots, int heightDots) {
        return switch (symbology) {
            case CODE128 -> (CODE128_FIXED_INK_MODULES
                             + value.length() * CODE128_DATA_MODULES_PER_CHAR
                             + BARCODE_QUIET_MODULES) * moduleWidthDots;
            case CODE39  -> (CODE39_CHAR_MODULES * (value.length() + 2)
                             + BARCODE_QUIET_MODULES) * moduleWidthDots;
            case QR      -> qrMagnification(heightDots) * qrModuleCount(value.length());
        };
    }

    /**
     * Returns the blank quiet-zone portion of the scan-code footprint in dots.
     * The crew-band layout subtracts this from one side so the text block
     * appears visually centered between the barcode and the logo.
     */
    public static int quietZoneDots(CodeSymbology symbology, int moduleWidthDots) {
        return switch (symbology) {
            case CODE128, CODE39 -> BARCODE_QUIET_MODULES * moduleWidthDots;
            case QR              -> 4 * qrMagnification(270);
        };
    }

    /**
     * Returns the X-axis extent (across the band width) of the scan code in dots.
     * Use this to center the code: {@code x = (bandWidth - extent) / 2}.
     */
    public static int estimateCrossBandExtent(CodeSymbology symbology, int heightDots, String value) {
        return switch (symbology) {
            case CODE128, CODE39 -> heightDots;
            case QR              -> qrMagnification(heightDots) * qrModuleCount(value.length());
        };
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static void appendCode128(StringBuilder zpl, String value, int x, int y,
                                        int heightDots, int moduleWidthDots,
                                        boolean showHumanReadable) {
        String hri = showHumanReadable ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BY%d", moduleWidthDots));
        zpl.append(String.format("^BCB,%d,%s,N,N", heightDots, hri));
        zpl.append(String.format("^FD%s^FS", value));
    }

    private static void appendCode39(StringBuilder zpl, String value, int x, int y,
                                       int heightDots, int moduleWidthDots,
                                       boolean showHumanReadable) {
        String hri = showHumanReadable ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BY%d", moduleWidthDots));
        // ^B3B = Code 39, B = bottom-up, N = normal check digit, height, hri, line
        zpl.append(String.format("^B3B,N,%d,%s,N", heightDots, hri));
        zpl.append(String.format("^FD%s^FS", value));
    }

    private static void appendQr(StringBuilder zpl, String value, int x, int y, int heightDots) {
        int mag = qrMagnification(heightDots);
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BQN,2,%d", mag));
        zpl.append(String.format("^FDMA,%s^FS", value));
    }

    /**
     * Derives QR magnification from the desired side length in dots.
     * Clamped to [2, 10] per the ZPL spec.
     */
    static int qrMagnification(int heightDots) {
        return Math.max(QR_MIN_MAGNIFICATION, Math.min(QR_MAX_MAGNIFICATION, heightDots / 25));
    }

    /**
     * Minimum QR module grid side for the given data length (simplified).
     * Version 1 = 21, version 2 = 25, etc.
     */
    static int qrModuleCount(int dataLength) {
        if (dataLength <= 25) return 25;
        if (dataLength <= 47) return 29;
        if (dataLength <= 77) return 33;
        return 41;
    }

    /** Removes ZPL control characters. */
    private static String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
