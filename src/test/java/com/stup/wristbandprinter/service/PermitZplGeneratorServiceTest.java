package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PermitZplGeneratorServiceTest {

    @Mock LogoConversionService stuplogoService;

    private WristbandProperties props;
    private PermitZplGeneratorService service;

    @BeforeEach
    void setUp() {
        lenient().when(stuplogoService.getGfCommand()).thenReturn("^GFA,8,8,1,FF");
        lenient().when(stuplogoService.getLogoHeightDots()).thenReturn(100);

        props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLengthDots(3300);
        props.setLogoSideMarginDots(75);

        service = new PermitZplGeneratorService(props, stuplogoService);
    }

    private PermitWristbandData sampleData(boolean hasCode) {
        return new PermitWristbandData(
            "Pukkelpop 2026",
            "ELEKTRICITEIT",
            null,
            hasCode ? "EL-001" : null,
            CodeSymbology.CODE128,
            "#FFFFFF"
        );
    }

    @Test
    void generate_producesZplWithStartAndEnd() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsPermitLabel() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("Toelating ELEKTRICITEIT");
    }

    @Test
    void generate_uppercasesPermitLabel() {
        PermitWristbandData data = new PermitWristbandData(
            "Event", "Elektriciteit", null, null, CodeSymbology.CODE128, "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).contains("Toelating ELEKTRICITEIT");
        assertThat(zpl).doesNotContain("Elektriciteit");
    }

    @Test
    void generate_containsEventName() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("Pukkelpop 2026");
    }

    @Test
    void generate_containsStuplogoGfCommand() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^GFA,8,8,1,FF");
    }

    @Test
    void generate_withoutCode_doesNotContainBarcodeCommand() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).doesNotContain("^BC").doesNotContain("^BQ");
    }

    @Test
    void generate_withCode_containsCode128Command() {
        String zpl = service.generate(sampleData(true));
        assertThat(zpl).contains("^BCB");
    }

    @Test
    void generate_noClub_line2IsAanPlusDots() {
        String zpl = service.generate(sampleData(false));
        // Line 2 is "aan " + dotted fill-in line — check the prefix and at least 5 dots
        assertThat(zpl).contains("aan .....");
    }

    @Test
    void generate_withClub_line2IsAanPlusClubName() {
        PermitWristbandData data = new PermitWristbandData(
            "Pukkelpop 2026", "PARKING", "STUP vzw", null, CodeSymbology.CODE128, "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).contains("aan STUP vzw");
        // Dots should NOT appear when a club name is given
        assertThat(zpl).doesNotContain(".....");
    }

    @Test
    void block2_bothLinesCentreJustifiedInTheSameFieldBlock() {
        // The two block-2 lines are centred to each other along the band length (Y) by rendering
        // both inside an identical ^FB block (same origin Y, same length, centre justification),
        // so ZPL does the centring and it stays correct regardless of each line's rendered length
        // (e.g. a narrow dotted "aan …" line vs the longer "Toelating …" line).
        String zpl = service.generate(sampleData(false));

        var m1 = java.util.regex.Pattern
            .compile("\\^FO(\\d+),(\\d+)\\^A0B,66,66\\^FB(\\d+),1,0,C,0\\^FD(Toelating[^^]*)\\^FS").matcher(zpl);
        var m2 = java.util.regex.Pattern
            .compile("\\^FO(\\d+),(\\d+)\\^A0B,42,42\\^FB(\\d+),1,0,C,0\\^FD(aan[^^]*)\\^FS").matcher(zpl);
        assertThat(m1.find()).isTrue();
        assertThat(m2.find()).isTrue();

        int x1 = Integer.parseInt(m1.group(1)), y1 = Integer.parseInt(m1.group(2)), len1 = Integer.parseInt(m1.group(3));
        int x2 = Integer.parseInt(m2.group(1)), y2 = Integer.parseInt(m2.group(2)), len2 = Integer.parseInt(m2.group(3));

        // Identical field-block origin (Y) and length → ZPL centres both lines on the same axis.
        assertThat(y1).isEqualTo(y2);
        assertThat(len1).isEqualTo(len2);

        // Still stacked one inter-line gap apart across the width (defaults 66 + 12).
        assertThat(x2 - x1).isEqualTo(66 + 12);
    }

    @Test
    void dottedFillInLineDoesNotInflateBlock2Height() {
        // Regression: a dotted "aan …" line renders far narrower than its character count, so it must
        // not inflate block-2 height past the longer "Toelating <label>" line. (A naive char-count
        // estimate made the no-club band reserve much more height than a club band, widening the gap
        // to the event name only when no club name was supplied.)
        props.getPermit().getText().setDotCount(45);
        int block2Len = block2FieldBlockLength(service.generate(sampleData(false)));

        // "Toelating ELEKTRICITEIT" (font 66) is the longest line and must drive block-2 height.
        int labelExtent = (int) ("Toelating ELEKTRICITEIT".length() * 66 * PermitZplGeneratorService.CHAR_ADVANCE_RATIO);
        assertThat(block2Len).isEqualTo(labelExtent);
    }

    @Test
    void eventNameStartsOneConfiguredGapAfterBlock2() {
        // The event name's start Y = (end of block 2's longest line) + the configured between-blocks
        // gap, so the spacing stays correct whatever the dot count / club name is.
        String zpl = service.generate(sampleData(false));

        var b2 = java.util.regex.Pattern
            .compile("\\^FO\\d+,(\\d+)\\^A0B,66,66\\^FB(\\d+),1,0,C,0").matcher(zpl);
        assertThat(b2.find()).isTrue();
        int block2Y = Integer.parseInt(b2.group(1));
        int block2Len = Integer.parseInt(b2.group(2));

        var ev = java.util.regex.Pattern.compile("\\^FO\\d+,(\\d+)\\^A0B,52,52\\^FD").matcher(zpl);
        assertThat(ev.find()).isTrue();
        int eventY = Integer.parseInt(ev.group(1));

        assertThat(eventY).isEqualTo(block2Y + block2Len + props.getPermit().getMargins().getBetweenBlocks());
    }

    private static int block2FieldBlockLength(String zpl) {
        var m = java.util.regex.Pattern.compile("\\^A0B,66,66\\^FB(\\d+),").matcher(zpl);
        assertThat(m.find()).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test
    void generate_containsRotationCommand_A0B_forPermitLabel() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^A0B");
    }

    @Test
    void generate_containsRotationCommand_A0B_forEventName() {
        // Event name runs along the band length (^A0B, 270°/bottom-up), reading the same
        // direction as block 2, not across the narrow width (^A0R/^A0I), so it never overflows.
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^A0B");
        assertThat(zpl).doesNotContain("^A0R");
        assertThat(zpl).doesNotContain("^A0I");
    }

    @Test
    void generate_doesNotIncludeEventLogo() {
        String zpl = service.generate(sampleData(false));
        // Only the STUP logo is rendered — the event logo was removed, so exactly one ^GF command
        int firstGf  = zpl.indexOf("^GFA");
        int secondGf = zpl.indexOf("^GFA", firstGf + 1);
        assertThat(firstGf).isGreaterThanOrEqualTo(0);
        assertThat(secondGf).isEqualTo(-1);
    }

    @Test
    void generate_sanitizesCaretInPermitLabel() {
        PermitWristbandData data = new PermitWristbandData(
            "Event", "ELEKTRI^CITEIT", null, null, CodeSymbology.CODE128, "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).doesNotContain("ELEKTRI^CITEIT");
        assertThat(zpl).contains("ELEKTRICITEIT");
    }
}
