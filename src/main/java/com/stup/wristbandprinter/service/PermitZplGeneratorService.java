package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitMargins;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitText;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitCode;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Generates ZPL for permit wristbands (campsite resource access: electricity, parking, …).
 *
 * <h2>Band layout (top to bottom along Y axis)</h2>
 * <ol>
 *   <li>Block 1 — STUP logo (pre-rotated 180°, same asset as the crew band)</li>
 *   <li>Block 2 — Permission group: "Toelating [permitLabel]" (^A0B) + inter-line gap +
 *       "aan " + dotted fill-in line or clubName (^A0B); both lines centred along Y via ^FB</li>
 *   <li>Block 3 — Optional scan code (only when {@code codeValue} is present)</li>
 *   <li>Block 4 — Event name: eventName (^A0B, 270°)</li>
 * </ol>
 *
 * All blocks are centered vertically as one group. When block 3 is absent it is
 * excluded from the total-height sum and the surrounding margins are also dropped,
 * so the remaining three blocks re-center correctly.
 *
 * <h2>Text rotation conventions</h2>
 * <p>The band is narrow (width) and long (length). Only the {@code ^A0R}/{@code ^A0B}
 * rotations run text <em>along the length</em>; {@code ^A0N}/{@code ^A0I} run text across
 * the narrow width and overflow for anything but the shortest strings.</p>
 * <ul>
 *   <li>Block 2 text: {@code ^A0B} (270°, "bottom-up") — same as the crew band text</li>
 *   <li>Block 4 eventName: {@code ^A0B} (270°, "bottom-up") — runs along the band length and
 *       reads in the same direction as block 2 (^A0N/^A0I would overflow the narrow width)</li>
 * </ul>
 */
@Slf4j
@Profile("!worker")
@Service
public class PermitZplGeneratorService {

    // Same advance ratio as ZplGeneratorService — shared font calibration.
    // For ^A0I (inverted/180°) the axis is the same as ^A0B so the ratio is identical.
    static final double CHAR_ADVANCE_RATIO = ZplGeneratorService.CHAR_ADVANCE_RATIO;

    // '.' renders far narrower than an average glyph — measured ≈0.295 of the font size against
    // Labelary (ZP font 0). A slightly conservative 0.31 keeps a dotted fill-in line's reserved
    // length ≥ its actual rendered length, so it never over-inflates block 2 (which used to widen
    // the gap to the event name only when no club name was supplied) nor wraps inside its ^FB field.
    static final double DOT_ADVANCE_RATIO = 0.31;

    // The gap between the two block-2 lines is configured via
    // WristbandProperties.Permit.PermitMargins.interLineGap.

    private final WristbandProperties props;
    private final LogoConversionService stuplogoService;

    public PermitZplGeneratorService(WristbandProperties props,
                                     LogoConversionService stuplogoService) {
        this.props = props;
        this.stuplogoService = stuplogoService;
    }

    public String generate(PermitWristbandData data) {
        PermitMargins margins = props.getPermit().getMargins();
        PermitText text      = props.getPermit().getText();
        PermitCode code      = props.getPermit().getCode();

        int bandWidth  = props.getWidthDots();
        int bandLength = props.getLengthDots();
        int sideMargin = props.getLogoSideMarginDots();

        // ── Block heights ─────────────────────────────────────────────────────────
        int block1H = stuplogoService.getLogoHeightDots();

        // Block 2: "Toelating [permitLabel]" line + inter-line gap + "aan …" line
        int labelLineLen = lineExtent(permitLabelLine(data), text.getFontSizePermitLabel());
        String clubContent = clubContent(data, text);
        int clubLineLen = lineExtent(clubContent, text.getFontSizeClub());
        int block2H = Math.max(labelLineLen, clubLineLen); // Y extent (along band) = longest of the two lines

        // Block 3 (optional): scan code
        boolean hasCode = data.codeValue() != null && !data.codeValue().isBlank();
        int block3H = hasCode
            ? ScanCodeRenderer.estimateYLength(data.codeValue(), data.symbology(),
                    code.getModuleWidthDots(), code.getHeightDots())
            : 0;

        // Block 4: eventName text (Y extent = eventName only; the event logo was removed)
        int block4H = lineExtent(sanitize(data.eventName()), text.getFontSizeEventName());

        // ── Total height and origin ───────────────────────────────────────────────
        int totalH = block1H
            + margins.getBetweenBlocks()
            + block2H
            + margins.getBetweenBlocks()
            + (hasCode ? block3H + margins.getBetweenBlocks() : 0)
            + block4H;

        int topY = (bandLength - totalH) / 2;

        int block1Y = topY;
        int block2Y = block1Y + block1H + margins.getBetweenBlocks();
        int block3Y = block2Y + block2H + margins.getBetweenBlocks();
        int block4Y = hasCode
            ? block3Y + block3H + margins.getBetweenBlocks()
            : block2Y + block2H + margins.getBetweenBlocks();

        // ── ZPL assembly ──────────────────────────────────────────────────────────
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", bandWidth));
        zpl.append(String.format("^LL%d", bandLength));
        zpl.append("^CI28"); // UTF-8 encoding

        // Block 1: STUP logo
        appendLogo(zpl, sideMargin, block1Y, stuplogoService.getGfCommand());

        // Block 2: permission group
        appendBlock2(zpl, data, block2Y, text, margins, bandWidth);

        // Block 3: optional scan code
        if (hasCode) {
            appendScanCode(zpl, data, block3Y, code, bandWidth);
        }

        // Block 4: event name
        appendBlock4(zpl, data, block4Y, text, bandWidth);

        zpl.append("^XZ");
        return zpl.toString();
    }

    // ── Block renderers ───────────────────────────────────────────────────────────

    private void appendLogo(StringBuilder zpl, int x, int y, String gfCommand) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(gfCommand);
    }

    /**
     * Block 2: "Toelating [permitLabel]" + "aan …" (clubName or dotted fill-in line).
     *
     * Both text fields use ^A0B (270° = bottom-up rotation): font height is the X-direction
     * extent and the text grows in +Y. The two lines are stacked across the band width (one
     * inter-line gap apart) and centred on the same axis along the band length (Y) by rendering
     * each inside an identical ^FB field block with centre justification — see
     * {@link #appendCenteredLine}.
     */
    private void appendBlock2(StringBuilder zpl, PermitWristbandData data,
                               int blockY, PermitText text,
                               PermitMargins margins, int bandWidth) {
        int h1 = text.getFontSizePermitLabel();
        int h2 = text.getFontSizeClub();
        int gap = margins.getInterLineGap();

        // Stack the two lines across the band width (^A0B: font height is the X-direction extent),
        // one inter-line gap apart.
        int totalX = h1 + gap + h2;
        int groupX = (bandWidth - totalX) / 2;

        String labelText = permitLabelLine(data);
        String clubText = clubContent(data, text);

        // Centre the two lines on the same axis along the band length (Y). Both render inside an
        // identical ^FB block — same origin Y (blockY), same length (blockLen), centre-justified —
        // so ZPL itself centres each line within that block. The lines therefore share one centre
        // regardless of their true rendered length, so a short dotted "aan …" line and the longer
        // "Toelating …" line stay centred to each other and changing the dot count needs no recalc.
        // blockLen equals the block-2 height reserved by generate(), so the pair sits centred in
        // the block-2 slot. (Manual length estimation mis-centred narrow dot runs — see git history.)
        int blockLen = Math.max(lineExtent(labelText, h1), lineExtent(clubText, h2));

        appendCenteredLine(zpl, groupX, blockY, h1, blockLen, labelText);
        appendCenteredLine(zpl, groupX + h1 + gap, blockY, h2, blockLen, clubText);
    }

    /**
     * One ^A0B (270°) text line centre-justified along the band length (Y) within a ^FB block of
     * {@code blockLen} dots. Two lines that share the same {@code y} and {@code blockLen} are
     * centred on the same axis by ZPL, independent of each line's rendered length.
     */
    private void appendCenteredLine(StringBuilder zpl, int x, int y, int fontSize,
                                    int blockLen, String text) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^A0B,%d,%d", fontSize, fontSize));
        zpl.append(String.format("^FB%d,1,0,C,0", blockLen));
        zpl.append(String.format("^FD%s^FS", text));
    }

    /** Block 3: scan code, centered across band width. */
    private void appendScanCode(StringBuilder zpl, PermitWristbandData data,
                                 int blockY, PermitCode code, int bandWidth) {
        int crossExtent = ScanCodeRenderer.estimateCrossBandExtent(
                data.symbology(), code.getHeightDots(), data.codeValue());
        int x = (bandWidth - crossExtent) / 2;
        ScanCodeRenderer.appendTo(zpl, data.codeValue(), data.symbology(),
                x, blockY, code.getHeightDots(), code.getModuleWidthDots(),
                code.isShowHumanReadable());
    }

    /**
     * Block 4: eventName (^A0B, 270°/bottom-up).
     *
     * ^A0B runs the text <em>along the band length</em> (Y axis), just like block 2,
     * so long event names no longer overflow the narrow band width. It reads in the same
     * (270°, "bottom-up") direction as block 2. Block 4 is just the event name now
     * (the event logo was removed), so it starts at the block's top (blockY).
     */
    private void appendBlock4(StringBuilder zpl, PermitWristbandData data,
                               int blockY, PermitText text, int bandWidth) {
        int h = text.getFontSizeEventName();
        String eventText = sanitize(data.eventName());

        // Center event name across band width (^A0B: font height is the X-direction extent)
        int x = (bandWidth - h) / 2;
        zpl.append(String.format("^FO%d,%d", x, blockY));
        zpl.append(String.format("^A0B,%d,%d", h, h));
        zpl.append(String.format("^FD%s^FS", eventText));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Block-2 line 1: "Toelating " + the permit label, upper-cased. */
    private String permitLabelLine(PermitWristbandData data) {
        return "Toelating " + sanitize(data.permitLabel()).toUpperCase(Locale.ROOT);
    }

    private String clubContent(PermitWristbandData data, PermitText text) {
        String club = data.clubName();
        String tail = (club == null || club.isBlank())
            ? ".".repeat(text.getDotCount())
            : sanitize(club);
        return "aan " + tail;
    }

    /**
     * Estimated rendered length (along the band, Y) of one ^A0B text line. Normal glyphs advance at
     * {@link #CHAR_ADVANCE_RATIO}; the '.' fill-in dots are far narrower ({@link #DOT_ADVANCE_RATIO}),
     * so a long dotted line is not over-reserved. This keeps block 2's height — and therefore the gap
     * from the longest line to the event name — accurate regardless of the dot count or club name.
     */
    private int lineExtent(String text, int fontSize) {
        double units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += (text.charAt(i) == '.') ? DOT_ADVANCE_RATIO : CHAR_ADVANCE_RATIO;
        }
        return (int) (units * fontSize);
    }

    private String
    sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\^~]", "");
    }
}
