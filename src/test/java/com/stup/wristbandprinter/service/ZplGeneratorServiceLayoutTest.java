package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Layout/geometry guarantees for the legacy fixed wristband layout.
 * Uses production-like dimensions (300 dpi) and a long name to exercise the text block.
 */
@ExtendWith(MockitoExtension.class)
class ZplGeneratorServiceLayoutTest {

    @Mock
    private LogoConversionService logo;

    private WristbandProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(logo.getGfCommand()).thenReturn("^GFA,8,8,1,FF");
        lenient().when(logo.getLogoHeightDots()).thenReturn(150);

        props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLengthDots(3300);
        props.setLogoSideMarginDots(75);
        props.getMargins().setBetweenLogoAndBarcode(45);
        props.getMargins().setBetweenBarcodeAndText(45);
        props.getMargins().setBetweenTextAndLogo(45);
        props.getText().setFontSizeEvent(45);
        props.getText().setFontSizeName(74);
        props.getText().setFontSizeClub(45);
        props.getBarcode().setHeightDots(270);
        props.getBarcode().setShowHumanReadable(false);
    }

    private WristbandData longNameData() {
        return new WristbandData(
            "Pukkelpop 2026", "Jonathan", "Vandenberghe", "KSA Sint-Niklaas", "1234567890");
    }

    /** ^FOx,y preceding a ^BC (optionally with a ^BY prefix). */
    private int barcodeY(String zpl) {
        Matcher m = Pattern.compile("\\^FO(\\d+),(\\d+)\\^(?:BY\\d+\\^)?BC").matcher(zpl);
        assertThat(m.find()).as("barcode field origin present").isTrue();
        return Integer.parseInt(m.group(2));
    }

    /** Smallest y among the three ^A0 text lines — the line nearest the (top) barcode. */
    private int topmostTextY(String zpl) {
        Matcher m = Pattern.compile("\\^FO(\\d+),(\\d+)\\^A0").matcher(zpl);
        int min = Integer.MAX_VALUE;
        while (m.find()) min = Math.min(min, Integer.parseInt(m.group(2)));
        assertThat(min).as("at least one text line present").isLessThan(Integer.MAX_VALUE);
        return min;
    }

    /** Largest y of an ^FO that precedes a ^GFA (logo) — the bottom logo's origin. */
    private int bottomLogoY(String zpl) {
        Matcher m = Pattern.compile("\\^FO(\\d+),(\\d+)\\^GFA").matcher(zpl);
        int max = -1;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(2)));
        assertThat(max).as("bottom logo present").isGreaterThanOrEqualTo(0);
        return max;
    }

    private int lineExtent(int charCount, int fontSize) {
        return (int) (charCount * fontSize * ZplGeneratorService.CHAR_ADVANCE_RATIO);
    }

    @Test
    void barcodeEmitsConfiguredModuleWidthBeforeBarcodeCommand() {
        props.getBarcode().setModuleWidthDots(4);
        ZplGeneratorService service = new ZplGeneratorService(props, logo);

        String zpl = service.generate(longNameData());

        // ^BY sets the narrow-bar (module) width; for a rotated barcode this lengthens it
        // along the band's long side and makes it scannable. Must precede ^BCB.
        assertThat(zpl).contains("^BY4");
        assertThat(zpl.indexOf("^BY4"))
            .as("^BY must come before ^BCB")
            .isLessThan(zpl.indexOf("^BCB")).isGreaterThanOrEqualTo(0);
    }

    /** Visible (ink-to-ink) gap from the last barcode bar to the nearest text line. */
    private int visibleBarcodeToTextGap(String zpl, WristbandData data) {
        // Ink modules exclude the 20 quiet-zone modules (start 11 + check 11 + stop 13 + n*11).
        int inkLen = (35 + data.barcodeValue().length() * 11) * 2; // module width 2, no HRI
        return topmostTextY(zpl) - (barcodeY(zpl) + inkLen);
    }

    /** Visible (ink-to-ink) gap from the last text line to the bottom logo. */
    private int visibleTextToLogoGap(String zpl, WristbandData data) {
        int nameLen = (data.firstName() + " " + data.lastName()).length();
        int textBottom = topmostTextY(zpl) + lineExtent(nameLen, props.getText().getFontSizeName());
        return bottomLogoY(zpl) - textBottom;
    }

    @Test
    void visibleBarcodeToTextGapIsBoundedNotHundredsOfDots() {
        ZplGeneratorService service = new ZplGeneratorService(props, logo);
        WristbandData data = longNameData();

        int gap = visibleBarcodeToTextGap(service.generate(data), data);

        // Margin (45) + barcode quiet zone (20 modules * 2 = 40) ≈ 85, not the old ~500.
        assertThat(gap)
            .as("visible barcode->text gap should be margin + quiet zone (~85)")
            .isBetween(70, 100);
    }

    @Test
    void visibleTextToLogoGapEqualsVisibleBarcodeToTextGap() {
        ZplGeneratorService service = new ZplGeneratorService(props, logo);
        WristbandData data = longNameData();
        String zpl = service.generate(data);

        int topGap = visibleBarcodeToTextGap(zpl, data);
        int bottomGap = visibleTextToLogoGap(zpl, data);

        // User's requirement: the text block is centered — equal *visible* whitespace on both
        // sides. Holds because the layout mirrors the barcode's quiet zone onto the bottom gap.
        assertThat(bottomGap)
            .as("text->logo visible gap should equal the barcode->text visible gap")
            .isCloseTo(topGap, org.assertj.core.data.Offset.offset(6));
    }
}
