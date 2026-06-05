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
        int barcodeLen = estimateBarcodeYLength(data.barcodeValue());
        int textLen    = textBlockYLength(data, textBlock);

        // The barcode's quiet zone is blank space inside its footprint, on the text-facing side.
        // Mirror it onto the bottom gap so equal margins yield equal *visible* whitespace and the
        // text block sits centered between the barcode and the bottom logo.
        int quietZone  = barcodeQuietZoneDots();

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

    private void appendBarcode(StringBuilder zpl, String value, int y,
                                WristbandProperties.Barcode b) {
        // Center the barcode in band width using its height as the x-extent
        int x = centerX(b.getHeightDots());
        String hri = b.isShowHumanReadable() ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        // ^BY<moduleWidth> — widen the narrow bar; lengthens the rotated barcode along the band
        // and improves scanner readability.
        zpl.append(String.format("^BY%d", b.getModuleWidthDots()));
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

    // Code 128 module budget. Ink (printed bars): start(11) + check(11) + stop(13) + n×11 data.
    // Quiet zone (blank): 20 modules. Footprint = ink + quiet.
    private static final int BARCODE_FIXED_INK_MODULES = 35; // start + check + stop
    private static final int BARCODE_QUIET_MODULES = 20;     // blank quiet zone

    // Estimate the full Y footprint (along band) of a 90°-rotated Code 128 barcode, in dots.
    private int estimateBarcodeYLength(String value) {
        int modules = BARCODE_FIXED_INK_MODULES + value.length() * 11 + BARCODE_QUIET_MODULES;
        int hri = props.getBarcode().isShowHumanReadable() ? 59 : 0;
        return modules * props.getBarcode().getModuleWidthDots() + hri;
    }

    // Blank quiet-zone portion of the barcode footprint, in dots.
    private int barcodeQuietZoneDots() {
        return BARCODE_QUIET_MODULES * props.getBarcode().getModuleWidthDots();
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
