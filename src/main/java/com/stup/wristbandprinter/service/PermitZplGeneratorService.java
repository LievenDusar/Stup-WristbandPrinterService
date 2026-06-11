package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitMargins;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitText;
import com.stup.wristbandprinter.config.WristbandProperties.Permit.PermitCode;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Generates ZPL for permit wristbands (campsite resource access: electricity, parking, …).
 *
 * <h2>Band layout (top to bottom along Y axis)</h2>
 * <ol>
 *   <li>Block 1 — STUP logo (pre-rotated 180°, same asset as the crew band)</li>
 *   <li>Block 2 — Permission group: "Toelating [permitLabel]" (^A0B) + writing-space gap +
 *       dotted fill-in line or associationName (^A0B)</li>
 *   <li>Block 3 — Optional scan code (only when {@code codeValue} is present)</li>
 *   <li>Block 4 — Event group: eventName (^A0B, 270°) + event logo (pre-rotated 180°)
 *       (logo omitted when {@code PermitEventLogoService} has no logo loaded)</li>
 * </ol>
 *
 * All four blocks are centered vertically as one group. When block 3 is absent it is
 * excluded from the total-height sum and the surrounding margins are also dropped,
 * so the remaining three blocks re-center correctly. When the event logo is missing
 * (logo service reports not available), block 4 contains only the event-name text.
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

    // Gap between the permit-label text field and the dot/association field inside block 2.
    // The writing-space gap is configured via WristbandProperties.Permit.PermitMargins.writingSpaceGap.

    private final WristbandProperties props;
    private final LogoConversionService stuplogoService;
    private final PermitEventLogoService eventLogoService;

    public PermitZplGeneratorService(WristbandProperties props,
                                     LogoConversionService stuplogoService,
                                     PermitEventLogoService eventLogoService) {
        this.props = props;
        this.stuplogoService = stuplogoService;
        this.eventLogoService = eventLogoService;
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

        // Block 2: "Toelating [permitLabel]" line + writing-space gap + dot/assoc line
        int labelLineLen = lineExtent("Toelating " + sanitize(data.permitLabel()), text.getFontSizePermitLabel());
        String assocContent = assocContent(data, text);
        int assocLineLen = lineExtent(assocContent, text.getFontSizeAssociation());
        int block2H = Math.max(labelLineLen, assocLineLen); // Y extent (along band) = longest of the two lines

        // Block 3 (optional): scan code
        boolean hasCode = data.codeValue() != null && !data.codeValue().isBlank();
        int block3H = hasCode
            ? ScanCodeRenderer.estimateYLength(data.codeValue(), data.symbology(),
                    code.getModuleWidthDots(), code.getHeightDots())
            : 0;

        // Block 4: eventName text + optional event logo
        int eventNameLen = lineExtent(sanitize(data.eventName()), text.getFontSizeEventName());
        boolean hasEventLogo = eventLogoService.isAvailable();
        int eventLogoH = hasEventLogo ? eventLogoService.getLogoHeightDots() : 0;
        // Block 4 Y extent = eventName + (gap inside block) + logo height
        int insideBlock4Gap = hasEventLogo ? margins.getBetweenBlocks() : 0;
        int block4H = Math.max(eventNameLen, 0) + (hasEventLogo ? insideBlock4Gap + eventLogoH : 0);

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

        // Block 4: event group
        appendBlock4(zpl, data, block4Y, text, margins, bandWidth, sideMargin, hasEventLogo, insideBlock4Gap);

        zpl.append("^XZ");
        return zpl.toString();
    }

    // ── Block renderers ───────────────────────────────────────────────────────────

    private void appendLogo(StringBuilder zpl, int x, int y, String gfCommand) {
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(gfCommand);
    }

    /**
     * Block 2: "Toelating [permitLabel]" + writing-space gap + dot/association line.
     *
     * Both text fields use ^A0B (270° = bottom-up rotation). With this rotation,
     * font height is the X-direction extent and the text grows in the +Y direction.
     * We group-center the two fields across the band width and center each line
     * along Y around the block's midpoint.
     */
    private void appendBlock2(StringBuilder zpl, PermitWristbandData data,
                               int blockY, PermitText text,
                               PermitMargins margins, int bandWidth) {
        int h1 = text.getFontSizePermitLabel();
        int h2 = text.getFontSizeAssociation();

        // Group-center both lines across band width
        int totalX = h1 + margins.getWritingSpaceGap() + h2;
        // Note: inside block 2 the "gap" between the two text columns is the writing-space gap
        // (which is the X distance between the two font columns in the ^A0B rotated plane).
        // Center the group:
        int groupX = (bandWidth - totalX) / 2;

        String labelText = "Toelating " + sanitize(data.permitLabel());
        String assocText = assocContent(data, text);

        int blockH = Math.max(lineExtent(labelText, h1), lineExtent(assocText, h2));
        int centerY = blockY + blockH / 2;

        // Permit-label line (left column in the group)
        int labelY = centerY - lineExtent(labelText, h1) / 2;
        zpl.append(String.format("^FO%d,%d", groupX, labelY));
        zpl.append(String.format("^A0B,%d,%d", h1, h1));
        zpl.append(String.format("^FD%s^FS", labelText));

        // Association / dot line (right column — after writing-space gap)
        int assocY = centerY - lineExtent(assocText, h2) / 2;
        zpl.append(String.format("^FO%d,%d", groupX + h1 + margins.getWritingSpaceGap(), assocY));
        zpl.append(String.format("^A0B,%d,%d", h2, h2));
        zpl.append(String.format("^FD%s^FS", assocText));
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
     * Block 4: eventName (^A0B, 270°/bottom-up) + event logo (pre-rotated 180°).
     *
     * ^A0B runs the text <em>along the band length</em> (Y axis), just like block 2,
     * so long event names no longer overflow the narrow band width. It reads in the same
     * (270°, "bottom-up") direction as block 2. ^A0B and ^A0R share an identical field
     * footprint (font height is the X-direction extent, the string grows in +Y from ^FO);
     * they differ only in reading direction, so this stays group-centered exactly as before.
     */
    private void appendBlock4(StringBuilder zpl, PermitWristbandData data,
                               int blockY, PermitText text, PermitMargins margins,
                               int bandWidth, int sideMargin,
                               boolean hasEventLogo, int insideBlock4Gap) {
        int h = text.getFontSizeEventName();
        String eventText = sanitize(data.eventName());
        int eventLen = lineExtent(eventText, h);

        // Center event name across band width (^A0B: font height is the X-direction extent)
        int x = (bandWidth - h) / 2;
        // Center event name along Y within block 4
        int block4H = eventLen + (hasEventLogo ? insideBlock4Gap + eventLogoService.getLogoHeightDots() : 0);
        int centerY = blockY + block4H / 2;
        int nameY = centerY - eventLen / 2;

        // Event name
        zpl.append(String.format("^FO%d,%d", x, nameY));
        zpl.append(String.format("^A0B,%d,%d", h, h));
        zpl.append(String.format("^FD%s^FS", eventText));

        // Event logo (if available)
        if (hasEventLogo) {
            int logoY = nameY + eventLen + insideBlock4Gap;
            appendLogo(zpl, sideMargin, logoY, eventLogoService.getGfCommand());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private String assocContent(PermitWristbandData data, PermitText text) {
        String assoc = data.associationName();
        if (assoc == null || assoc.isBlank()) {
            return ".".repeat(text.getDotCount());
        }
        return sanitize(assoc);
    }

    private int lineExtent(String text, int fontSize) {
        return (int) (text.length() * fontSize * CHAR_ADVANCE_RATIO);
    }

    private String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\^~]", "");
    }
}
