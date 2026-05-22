package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import org.springframework.stereotype.Service;

@Service
public class ZplGeneratorService {

    // Gap between text lines in dots (along band length, with ^A0B rotation)
    private static final int INTER_LINE_GAP = 8;

    // Approximate dots added by the Human Readable Interpretation text below a barcode
    private static final int HRI_HEIGHT_DOTS = 24;

    private final WristbandProperties props;
    private final LogoConversionService logoConversionService;

    public ZplGeneratorService(WristbandProperties props, LogoConversionService logoConversionService) {
        this.props = props;
        this.logoConversionService = logoConversionService;
    }

    public String generate(WristbandData data) {
        WristbandProperties.Margins m = props.getMargins();
        WristbandProperties.Text t = props.getText();
        WristbandProperties.Barcode b = props.getBarcode();

        int logoH = logoConversionService.getLogoHeightDots();
        int sideMargin = props.getLogoSideMarginDots();

        // Calculate y positions (y increases toward adhesive end)
        int topLogoY        = m.getTopDots();
        int textBlockY      = topLogoY + logoH + m.getBetweenLogoAndText();
        int barcodeY        = textBlockY + textBlockHeight(t) + m.getBetweenTextAndBarcode();
        int hriExtra        = b.isShowHumanReadable() ? HRI_HEIGHT_DOTS : 0;
        int bottomLogoY     = barcodeY + b.getHeightDots() + hriExtra + m.getBetweenBarcodeAndLogo();

        StringBuilder zpl = new StringBuilder();

        // Label setup
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28"); // UTF-8 encoding

        // Top logo — pre-rotated 180° in image data
        appendLogo(zpl, sideMargin, topLogoY);

        // Text block — ^A0B = 90° counter-clockwise rotation
        appendTextBlock(zpl, data, textBlockY, t);

        // Barcode — ^BCB = 90° counter-clockwise rotation
        appendBarcode(zpl, data.barcodeValue(), barcodeY, b);

        // Bottom logo — same pre-rotated image data
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
        // Event name — smaller font, centered in band width
        int eventX = centerX(t.getFontSizeEvent());
        int eventY = startY;
        zpl.append(String.format("^FO%d,%d", eventX, eventY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeEvent(), t.getFontSizeEvent()));
        zpl.append(String.format("^FD%s^FS", sanitize(data.eventName())));

        // Full name — larger font, centered in band width
        int nameX = centerX(t.getFontSizeName());
        int nameY = eventY + t.getFontSizeEvent() + INTER_LINE_GAP;
        zpl.append(String.format("^FO%d,%d", nameX, nameY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeName(), t.getFontSizeName()));
        zpl.append(String.format("^FD%s %s^FS", sanitize(data.firstName()), sanitize(data.lastName())));

        // Association — smaller font, centered in band width
        int assocX = centerX(t.getFontSizeAssociation());
        int assocY = nameY + t.getFontSizeName() + INTER_LINE_GAP;
        zpl.append(String.format("^FO%d,%d", assocX, assocY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeAssociation(), t.getFontSizeAssociation()));
        zpl.append(String.format("^FD%s^FS", sanitize(data.associationName())));
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

    private int textBlockHeight(WristbandProperties.Text t) {
        return t.getFontSizeEvent() + INTER_LINE_GAP
             + t.getFontSizeName() + INTER_LINE_GAP
             + t.getFontSizeAssociation();
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
