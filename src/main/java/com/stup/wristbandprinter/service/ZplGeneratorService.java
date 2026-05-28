package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ZplGeneratorService {

    // Gap between text lines in dots (across band width, with ^A0B rotation)
    private static final int INTER_LINE_GAP = 12;

    // ^A0 is a proportional font: actual Y advance per character is ~0.40 × font_size,
    // not the full font_size. Without this correction the block height is ~2.5× too large,
    // pushing shorter lines far away from the anchor line in center-alignment.
    private static final double CHAR_ADVANCE_RATIO = 0.40;

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
        int barcodeLen = estimateBarcodeYLength(data.barcodeValue());
        int textLen    = textBlockYLength(data, textBlock);

        // Layout: logo → barcode → text → logo
        // Center the entire block vertically on the band
        int totalHeight = logoH
            + margins.getBetweenLogoAndText()    // logo → barcode
            + barcodeLen
            + margins.getBetweenTextAndBarcode() // barcode → text
            + textLen
            + margins.getBetweenBarcodeAndLogo() // text → logo
            + logoH;
        int topLogoY    = (props.getLengthDots() - totalHeight) / 2;
        int barcodeY    = topLogoY + logoH + margins.getBetweenLogoAndText();
        int textBlockY  = barcodeY + barcodeLen + margins.getBetweenTextAndBarcode();
        int bottomLogoY = textBlockY + textLen + margins.getBetweenBarcodeAndLogo();

        StringBuilder zpl = new StringBuilder();

        // Label setup
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28"); // UTF-8 encoding

        appendLogo(zpl, sideMargin, topLogoY);
        appendBarcode(zpl, data.barcodeValue(), barcodeY, barCodeBlock);
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
        int eventY  = centerY - (int) (eventText.length() * h1 * CHAR_ADVANCE_RATIO) / 2;
        int nameY   = centerY - (int) (nameText.length()  * h2 * CHAR_ADVANCE_RATIO) / 2;
        int assocY  = centerY - (int) (assocText.length() * h3 * CHAR_ADVANCE_RATIO) / 2;

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

    private void appendBarcode(StringBuilder zpl, String value, int y,
                                WristbandProperties.Barcode b) {
        // Center the barcode in band width using its height as the x-extent
        int x = centerX(b.getHeightDots());
        String hri = b.isShowHumanReadable() ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        // ^BCB,height,hri,line,lineAbove — B = bottom-up (90° CCW)
        zpl.append(String.format("^BCB,%d,%s,N,N", b.getHeightDots(), hri));
        zpl.append(String.format("^FD%s^FS", sanitize(value)));
    }

    /**
     * Centers a field of the given height across the label width.
     * With ^A0B rotation, font height maps to the x-direction (across the band width).
     */
    private int centerX(int fieldHeight) {
        return (props.getWidthDots() - fieldHeight) / 2;
    }

    // Conservative Y extent for block placement — uses full font_size per character to ensure
    // subsequent elements (barcode, logo) never overlap the text block.
    private int textBlockYLength(WristbandData data, WristbandProperties.Text t) {
        int eventLen = sanitize(data.eventName()).length() * t.getFontSizeEvent();
        int nameLen  = (sanitize(data.firstName()) + " " + sanitize(data.lastName())).length() * t.getFontSizeName();
        int assocLen = sanitize(data.associationName()).length() * t.getFontSizeAssociation();
        return Math.max(eventLen, Math.max(nameLen, assocLen));
    }

    // Estimate the Y extent (along band) of a 90°-rotated Code 128 barcode at default module width (2 dots).
    // Code 128: start(11) + n×11 data + check(11) + stop(13) + quiet zones(20) modules × 2 dots/module.
    private int estimateBarcodeYLength(String value) {
        int modules = 55 + value.length() * 11; // 55 = start(11) + check(11) + stop(13) + quiet zones(20)
        int hri = props.getBarcode().isShowHumanReadable() ? 59 : 0;
        return modules * 2 + hri;
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
