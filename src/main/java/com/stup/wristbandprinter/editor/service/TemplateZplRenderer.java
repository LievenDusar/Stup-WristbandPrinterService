package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.CrossAlign;
import com.stup.wristbandprinter.editor.domain.DataBinding;
import com.stup.wristbandprinter.editor.domain.ElementType;
import com.stup.wristbandprinter.editor.domain.StackDirection;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.domain.TemplateElement;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TemplateDefinition} to ZPL. With a {@link WristbandData} the bound fields
 * carry real values; with {@link #renderTemplate} they carry {@code ${BINDING}} placeholders.
 * Groups are flattened recursively to absolute positions using each child's stored bounding box.
 */
@Profile("!worker")
@Service
public class TemplateZplRenderer {

    private static final int MAX_DEPTH = 20;

    private final TemplateAssetService assetService;

    public TemplateZplRenderer(TemplateAssetService assetService) {
        this.assetService = assetService;
    }

    public String render(TemplateDefinition def, WristbandData data) {
        return renderWith(def, toMap(data));
    }

    public String renderTemplate(TemplateDefinition def) {
        return renderWith(def, null);
    }

    private String renderWith(TemplateDefinition def, Map<DataBinding, String> data) {
        StringBuilder zpl = new StringBuilder();
        int bandWidth = def.canvas().widthDots();
        zpl.append("^XA");
        zpl.append("^PW").append(bandWidth);
        zpl.append("^LL").append(def.canvas().lengthDots());
        zpl.append("^CI28");
        for (TemplateElement el : def.elements()) {
            renderNode(el, el.x(), el.y(), data, zpl, 0, bandWidth);
        }
        zpl.append("^XZ");
        return zpl.toString();
    }

    private void renderNode(TemplateElement el, int absX, int absY,
                            Map<DataBinding, String> data, StringBuilder zpl, int depth, int bandWidth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Template group nesting exceeds " + MAX_DEPTH);
        }
        if (el.type() == ElementType.GROUP) {
            int originX = Boolean.TRUE.equals(el.centerOnBand())
                ? (bandWidth - sizeOf(el)[0]) / 2 : absX;
            layoutGroup(el, originX, absY, data, zpl, depth, bandWidth);
            return;
        }
        switch (el.type()) {
            case TEXT, STATIC_TEXT -> appendText(zpl, el, absX, absY, data, bandWidth);
            case BARCODE -> appendBarcode(zpl, el, centeredOriginX(el, absX, bandWidth), absY, data);
            case IMAGE -> appendImage(zpl, el, centeredOriginX(el, absX, bandWidth), absY);
            case SHAPE -> appendShape(zpl, el, centeredOriginX(el, absX, bandWidth), absY);
            default -> { /* GROUP handled above */ }
        }
    }

    private void layoutGroup(TemplateElement group, int originX, int originY,
                             Map<DataBinding, String> data, StringBuilder zpl, int depth, int bandWidth) {
        StackDirection dir = group.stackDirection() == null ? StackDirection.LENGTH : group.stackDirection();
        int margin = group.marginDots() == null ? 0 : group.marginDots();
        CrossAlign align = group.crossAlign() == null ? CrossAlign.START : group.crossAlign();
        List<TemplateElement> children = group.children() == null ? List.of() : group.children();

        int crossSize = 0;
        for (TemplateElement c : children) {
            crossSize = Math.max(crossSize, crossExtent(c, dir));
        }

        int cursor = 0;
        for (TemplateElement c : children) {
            int axis = axisExtent(c, dir);
            int cross = crossExtent(c, dir);
            int crossOffset = switch (align) {
                case START -> 0;
                case CENTER -> (crossSize - cross) / 2;
                case END -> crossSize - cross;
            };
            int cx, cy;
            if (dir == StackDirection.LENGTH) { cx = originX + crossOffset; cy = originY + cursor; }
            else { cx = originX + cursor; cy = originY + crossOffset; }
            renderNode(c, cx, cy, data, zpl, depth + 1, bandWidth);
            cursor += axis + margin;
        }
    }

    // ---- size helpers (dot-space bounding boxes) -----------------------------

    private int axisExtent(TemplateElement el, StackDirection parentDir) {
        int[] wh = sizeOf(el);
        return parentDir == StackDirection.LENGTH ? wh[1] : wh[0];
    }

    private int crossExtent(TemplateElement el, StackDirection parentDir) {
        int[] wh = sizeOf(el);
        return parentDir == StackDirection.LENGTH ? wh[0] : wh[1];
    }

    /** {width, height} in dots. Leaves use their stored box (rotation-aware footprint); groups compute theirs. */
    private int[] sizeOf(TemplateElement el) {
        if (el.type() != ElementType.GROUP) {
            int rot = ((el.rotation() % 360) + 360) % 360;
            return (rot == 90 || rot == 270)
                ? new int[]{el.heightDots(), el.widthDots()}
                : new int[]{el.widthDots(), el.heightDots()};
        }
        StackDirection dir = el.stackDirection() == null ? StackDirection.LENGTH : el.stackDirection();
        int margin = el.marginDots() == null ? 0 : el.marginDots();
        List<TemplateElement> children = el.children() == null ? List.of() : el.children();
        int along = 0, cross = 0;
        for (int i = 0; i < children.size(); i++) {
            int[] wh = sizeOf(children.get(i));
            int a = dir == StackDirection.LENGTH ? wh[1] : wh[0];
            int cr = dir == StackDirection.LENGTH ? wh[0] : wh[1];
            along += a;
            if (i < children.size() - 1) along += margin;
            cross = Math.max(cross, cr);
        }
        return dir == StackDirection.LENGTH ? new int[]{cross, along} : new int[]{along, cross};
    }

    /** Width-axis extent (dots) used for band-centering: rotation-aware footprint. */
    private int crossExtentDots(TemplateElement el) {
        int rot = ((el.rotation() % 360) + 360) % 360;
        return (rot == 90 || rot == 270) ? el.heightDots() : el.widthDots();
    }

    /** Centered x if flagged (band-width minus cross extent, halved); else the stored x. */
    private int centeredOriginX(TemplateElement el, int absX, int bandWidth) {
        return Boolean.TRUE.equals(el.centerOnBand())
            ? (bandWidth - crossExtentDots(el)) / 2 : absX;
    }

    // ---- leaf rendering (absolute positions) --------------------------------

    private void appendText(StringBuilder zpl, TemplateElement el, int x, int y, Map<DataBinding, String> data, int bandWidth) {
        int size = el.fontSize() == null ? 24 : el.fontSize();
        String font = el.font() == null ? "0" : el.font();
        String text = el.type() == ElementType.STATIC_TEXT ? sanitize(el.value()) : valueFor(el.binding(), data);
        boolean centered = Boolean.TRUE.equals(el.centerOnBand());
        int rot = ((el.rotation() % 360) + 360) % 360;

        if (centered && (rot == 0 || rot == 180)) {
            // ZPL field block centers the line across the full width, metric-free.
            zpl.append(String.format("^FO0,%d", y));
            zpl.append(String.format("^FB%d,1,0,C", bandWidth));
            zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
            zpl.append(String.format("^FD%s^FS", text));
            return;
        }
        int fox = x;
        if (centered) { // rot is 90 or 270 here (0/180 handled by ^FB above)
            // Cross extent of rotated ^A0 text = font height = size (matches ZplGeneratorService.centerX).
            fox = (bandWidth - size) / 2;
        }
        zpl.append(String.format("^FO%d,%d", fox, y));
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
    }

    private void appendBarcode(StringBuilder zpl, TemplateElement el, int x, int y, Map<DataBinding, String> data) {
        String hri = Boolean.TRUE.equals(el.showHumanReadable()) ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^BC%s,%d,%s,N,N", orientation(el.rotation()), el.heightDots(), hri));
        zpl.append(String.format("^FD%s^FS", valueFor(el.binding(), data)));
    }

    private void appendImage(StringBuilder zpl, TemplateElement el, int x, int y) {
        if (el.assetId() == null) return;
        String gf = assetService.gfCommand(el.assetId(), el.widthDots(), el.heightDots(), el.rotation());
        if (!gf.isEmpty()) {
            zpl.append(String.format("^FO%d,%d", x, y));
            zpl.append(gf);
        }
    }

    private void appendShape(StringBuilder zpl, TemplateElement el, int x, int y) {
        int thickness = el.thicknessDots() == null ? 1 : el.thicknessDots();
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(String.format("^GB%d,%d,%d^FS", el.widthDots(), el.heightDots(), thickness));
    }

    private String valueFor(DataBinding binding, Map<DataBinding, String> data) {
        if (binding == null) return "";
        if (data == null) return "${" + binding.name() + "}";
        return sanitize(data.getOrDefault(binding, ""));
    }

    private Map<DataBinding, String> toMap(WristbandData d) {
        Map<DataBinding, String> m = new EnumMap<>(DataBinding.class);
        m.put(DataBinding.EVENT_NAME, d.eventName());
        m.put(DataBinding.FIRST_NAME, d.firstName());
        m.put(DataBinding.LAST_NAME, d.lastName());
        m.put(DataBinding.FULL_NAME, d.firstName() + " " + d.lastName());
        m.put(DataBinding.CLUB_NAME, d.clubName());
        m.put(DataBinding.BARCODE_VALUE, d.barcodeValue());
        return m;
    }

    private char orientation(int rotation) {
        return switch (((rotation % 360) + 360) % 360) {
            case 90 -> 'R';
            case 180 -> 'I';
            case 270 -> 'B';
            default -> 'N';
        };
    }

    private String sanitize(String text) {
        return text == null ? "" : text.replaceAll("[\\^~]", "");
    }
}
