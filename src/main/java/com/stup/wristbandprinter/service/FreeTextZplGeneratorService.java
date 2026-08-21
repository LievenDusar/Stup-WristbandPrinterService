package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties.FreeText;
import com.stup.wristbandprinter.domain.FreeTextWristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Generates ZPL for free-text wristbands.
 *
 * <h2>Band layout (top to bottom along Y axis)</h2>
 * <ol>
 *   <li>Block 1 — STUP logo</li>
 *   <li>Block 2 — free text, one line, ^A0B rotation (runs along the band length, same
 *       convention as every other band's text)</li>
 *   <li>Block 3 — STUP logo (same asset/orientation as block 1)</li>
 * </ol>
 *
 * All three blocks are centered vertically as one group, with the configured
 * {@code betweenLogoAndText} gap applied symmetrically on both sides of the text block —
 * "same spacing between blocks as the other bands." The text is also centered across the
 * band width, like every other text block.
 */
@Slf4j
@Profile("!worker")
@Service
public class FreeTextZplGeneratorService {

    // Same advance ratio as the other generators — shared font calibration.
    static final double CHAR_ADVANCE_RATIO = ZplGeneratorService.CHAR_ADVANCE_RATIO;

    private final WristbandProperties props;
    private final LogoConversionService stuplogoService;

    public FreeTextZplGeneratorService(WristbandProperties props,
                                       LogoConversionService stuplogoService) {
        this.props = props;
        this.stuplogoService = stuplogoService;
    }

    public String generate(FreeTextWristbandData data) {
        FreeText freeText = props.getFreeText();

        int bandWidth  = props.getWidthDots();
        int bandLength = props.getLengthDots();
        int sideMargin = props.getLogoSideMarginDots();
        int gap        = freeText.getBetweenLogoAndText();

        // ── Block heights ─────────────────────────────────────────────────────────
        int logoH = stuplogoService.getLogoHeightDots();
        String text = sanitize(data.text());
        int textH = lineExtent(text, freeText.getFontSize());

        // ── Total height and origin ───────────────────────────────────────────────
        int totalH = logoH + gap + textH + gap + logoH;
        int topY = (bandLength - totalH) / 2;

        int block1Y = topY;
        int block2Y = block1Y + logoH + gap;
        int block3Y = block2Y + textH + gap;

        // ── ZPL assembly ──────────────────────────────────────────────────────────
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", bandWidth));
        zpl.append(String.format("^LL%d", bandLength));
        zpl.append("^CI28"); // UTF-8 encoding

        appendLogo(zpl, sideMargin, block1Y);
        appendText(zpl, text, block2Y, freeText.getFontSize(), bandWidth);
        appendLogo(zpl, sideMargin, block3Y);

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendLogo(StringBuilder zpl, int x, int y) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(stuplogoService.getGfCommand());
    }

    /** Free text: ^A0B (270°/bottom-up), group-centered across the band width. */
    private void appendText(StringBuilder zpl, String text, int y, int fontSize, int bandWidth) {
        int x = (bandWidth - fontSize) / 2;
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^A0B,%d,%d", fontSize, fontSize));
        zpl.append(String.format("^FD%s^FS", text));
    }

    /** Estimated rendered length (along the band, Y) of one ^A0B text line. */
    private int lineExtent(String text, int fontSize) {
        return (int) (text.length() * fontSize * CHAR_ADVANCE_RATIO);
    }

    private String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\^~]", "");
    }
}
