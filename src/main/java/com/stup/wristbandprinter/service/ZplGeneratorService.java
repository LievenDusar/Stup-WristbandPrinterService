package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Profile("!worker")
@Service
public class ZplGeneratorService {

    // Gap between text lines in dots (across band width, with ^A0B rotation)
    private static final int INTER_LINE_GAP = 12;

    // ^A0 is a proportional font: the actual Y advance per character is a fraction of font_size.
    // Calibrated against Labelary (ZP font 0, 12 dpmm): 33.5 dots/char at font 74 ⇒ ≈0.453.
    // This value sets the reserved text-block length. The text origin sits at the top of the
    // block and renders downward, so the *top* gap to the barcode is exact while the *bottom*
    // gap to the logo absorbs any error here — an accurate ratio keeps the two gaps equal.
    // Package-private so layout tests can assert against the same constant.
    static final double CHAR_ADVANCE_RATIO = 0.46;

    private final WristbandProperties props;
    private final LogoConversionService logoConversionService;

    public ZplGeneratorService(WristbandProperties props, LogoConversionService logoConversionService) {
        this.props = props;
        this.logoConversionService = logoConversionService;
    }

    public String generate(WristbandData data) {
        WristbandProperties.Margins margins = props.getMargins();
        WristbandProperties.Text textBlock = props.getText();
        WristbandProperties.Barcode barCodeBlock = props.getBarcode();

        int logoH      = logoConversionService.getLogoHeightDots();
        int sideMargin = props.getLogoSideMarginDots();
        int barcodeLen = ScanCodeRenderer.estimateYLength(
                data.barcodeValue(), data.codeSymbology(),
                barCodeBlock.getModuleWidthDots(), barCodeBlock.getHeightDots());
        int textLen    = textBlockYLength(data, textBlock);

        // The barcode's quiet zone is blank space inside its footprint, on the text-facing side.
        // Mirror it onto the bottom gap so equal margins yield equal *visible* whitespace and the
        // text block sits centered between the barcode and the bottom logo.
        int quietZone  = ScanCodeRenderer.quietZoneDots(
                data.codeSymbology(), barCodeBlock.getModuleWidthDots());

        // Layout: logo → barcode → text → logo
        // Center the entire block vertically on the band
        int totalHeight = logoH
            + margins.getBetweenLogoAndBarcode()
            + barcodeLen
            + margins.getBetweenBarcodeAndText()
            + textLen
            + margins.getBetweenTextAndLogo()
            + quietZone                          // mirror of the barcode quiet zone
            + logoH;
        int topLogoY    = (props.getLengthDots() - totalHeight) / 2;
        int barcodeY    = topLogoY + logoH + margins.getBetweenLogoAndBarcode();
        int textBlockY  = barcodeY + barcodeLen + margins.getBetweenBarcodeAndText();
        int bottomLogoY = textBlockY + textLen + margins.getBetweenTextAndLogo() + quietZone;

        StringBuilder zpl = new StringBuilder();

        // Label setup
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28"); // UTF-8 encoding

        appendLogo(zpl, sideMargin, topLogoY);
        appendBarcode(zpl, data, barcodeY, barCodeBlock);
        appendTextBlock(zpl, data, textBlockY, textBlock);
        appendLogo(zpl, sideMargin, bottomLogoY);

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendLogo(StringBuilder zpl, int x, int y) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(logoConversionService.getGfCommand());
    }

    private void appendTextBlock(StringBuilder zpl, WristbandData data,
                                  int startY, WristbandProperties.Text t) {
        int h1 = t.getFontSizeEvent();
        int h2 = t.getFontSizeName();
        int h3 = t.getFontSizeAssociation();

        // Group-center the three lines across the band width.
        // With ^A0B rotation, font height = character size in the X direction (across band).
        int totalXWidth = h1 + INTER_LINE_GAP + h2 + INTER_LINE_GAP + h3;
        int groupX = (props.getWidthDots() - totalXWidth) / 2;

        // Center each line around the midpoint of the longest line.
        // Shorter lines are offset so their midpoint equals the longest line's midpoint.
        // No line starts before the longest starts; no line ends after the longest ends.
        String eventText = sanitize(data.eventName());
        String nameText  = sanitize(data.firstName()) + " " + sanitize(data.lastName());
        String assocText = sanitize(data.associationName());

        int blockHeight = textBlockYLength(data, t);
        int centerY = startY + blockHeight / 2;
        int eventY  = centerY - lineExtent(eventText.length(), h1) / 2;
        int nameY   = centerY - lineExtent(nameText.length(),  h2) / 2;
        int assocY  = centerY - lineExtent(assocText.length(), h3) / 2;

        // Event name
        zpl.append(String.format("^FO%d,%d", groupX, eventY));
        zpl.append(String.format("^A0B,%d,%d", h1, h1));
        zpl.append(String.format("^FD%s^FS", eventText));

        // Full name — larger font
        zpl.append(String.format("^FO%d,%d", groupX + h1 + INTER_LINE_GAP, nameY));
        zpl.append(String.format("^A0B,%d,%d", h2, h2));
        zpl.append(String.format("^FD%s^FS", nameText));

        // Association
        zpl.append(String.format("^FO%d,%d", groupX + h1 + INTER_LINE_GAP + h2 + INTER_LINE_GAP, assocY));
        zpl.append(String.format("^A0B,%d,%d", h3, h3));
        zpl.append(String.format("^FD%s^FS", assocText));
    }

    private void appendBarcode(StringBuilder zpl, WristbandData data, int y,
                                WristbandProperties.Barcode b) {
        int crossExtent = ScanCodeRenderer.estimateCrossBandExtent(
                data.codeSymbology(), b.getHeightDots(), data.barcodeValue());
        int x = (props.getWidthDots() - crossExtent) / 2;
        ScanCodeRenderer.appendTo(zpl, data.barcodeValue(), data.codeSymbology(),
                x, y, b.getHeightDots(), b.getModuleWidthDots(), b.isShowHumanReadable());
    }

    // Y extent (along band) of the text block: the longest line's rendered length. Uses the same
    // CHAR_ADVANCE_RATIO as line positioning so the reserved block matches the actual text extent —
    // otherwise the block is ~2.5× too long and the surplus becomes fixed padding that swamps the
    // configurable barcode/logo margins.
    private int textBlockYLength(WristbandData data, WristbandProperties.Text t) {
        int eventLen = lineExtent(sanitize(data.eventName()).length(), t.getFontSizeEvent());
        int nameLen  = lineExtent((sanitize(data.firstName()) + " " + sanitize(data.lastName())).length(), t.getFontSizeName());
        int assocLen = lineExtent(sanitize(data.associationName()).length(), t.getFontSizeAssociation());
        return Math.max(eventLen, Math.max(nameLen, assocLen));
    }

    // Rendered length (along band) of a single ^A0B text line of the given character count.
    private int lineExtent(int charCount, int fontSize) {
        return (int) (charCount * fontSize * CHAR_ADVANCE_RATIO);
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
