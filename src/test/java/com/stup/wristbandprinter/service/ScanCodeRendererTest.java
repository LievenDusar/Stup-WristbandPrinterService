package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCodeRendererTest {

    // ── CODE128 ─────────────────────────────────────────────────────────────

    @Test
    void code128_appendTo_containsBCB() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "ABC123", CodeSymbology.CODE128, 10, 100, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^BCB,270,N,N,N");
        assertThat(out).contains("^BY3");
        assertThat(out).contains("^FDABC123^FS");
        assertThat(out).contains("^FO10,100");
    }

    @Test
    void code128_appendTo_showHumanReadable_usesY() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "123", CodeSymbology.CODE128, 0, 0, 270, 3, true);
        assertThat(zpl.toString()).contains("^BCB,270,Y,N,N");
    }

    @Test
    void code128_estimateYLength_growsWithDataLength() {
        int short_ = ScanCodeRenderer.estimateYLength("A", CodeSymbology.CODE128, 3, 270);
        int long_  = ScanCodeRenderer.estimateYLength("ABCDEFGHIJ", CodeSymbology.CODE128, 3, 270);
        assertThat(long_).isGreaterThan(short_);
    }

    @Test
    void code128_quietZone_isModuleWidthTimes20() {
        assertThat(ScanCodeRenderer.quietZoneDots(CodeSymbology.CODE128, 3)).isEqualTo(60);
        assertThat(ScanCodeRenderer.quietZoneDots(CodeSymbology.CODE128, 2)).isEqualTo(40);
    }

    @Test
    void code128_crossBandExtent_equalsHeightDots() {
        assertThat(ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.CODE128, 270, "ABC")).isEqualTo(270);
    }

    // ── CODE39 ──────────────────────────────────────────────────────────────

    @Test
    void code39_appendTo_containsB3B() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "HELLO", CodeSymbology.CODE39, 15, 200, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^B3B,N,270,N,N");
        assertThat(out).contains("^BY3");
        assertThat(out).contains("^FDHELLO^FS");
        assertThat(out).contains("^FO15,200");
    }

    @Test
    void code39_estimateYLength_growsWithDataLength() {
        int short_ = ScanCodeRenderer.estimateYLength("A",       CodeSymbology.CODE39, 3, 270);
        int long_  = ScanCodeRenderer.estimateYLength("ABCDEFG", CodeSymbology.CODE39, 3, 270);
        assertThat(long_).isGreaterThan(short_);
    }

    @Test
    void code39_crossBandExtent_equalsHeightDots() {
        assertThat(ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.CODE39, 270, "ABC")).isEqualTo(270);
    }

    // ── QR ──────────────────────────────────────────────────────────────────

    @Test
    void qr_appendTo_containsBQN() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "https://stup.be", CodeSymbology.QR, 50, 300, 270, 3, false);
        String out = zpl.toString();
        assertThat(out).contains("^BQN,2,");
        assertThat(out).contains("^FDMA,https://stup.be^FS");
        assertThat(out).contains("^FO50,300");
    }

    @Test
    void qr_crossBandExtent_isSquare_lessThanBandWidth300() {
        int extent = ScanCodeRenderer.estimateCrossBandExtent(CodeSymbology.QR, 270, "ABC123");
        assertThat(extent).isLessThanOrEqualTo(300);
        assertThat(extent).isGreaterThan(0);
    }

    // ── sanitize ────────────────────────────────────────────────────────────

    @Test
    void appendTo_sanitizesCaretAndTilde() {
        StringBuilder zpl = new StringBuilder();
        ScanCodeRenderer.appendTo(zpl, "A^B~C", CodeSymbology.CODE128, 0, 0, 270, 3, false);
        assertThat(zpl.toString()).contains("^FDABC^FS");
    }
}
