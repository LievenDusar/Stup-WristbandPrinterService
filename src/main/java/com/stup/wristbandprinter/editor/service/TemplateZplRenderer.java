package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.DataBinding;
import com.stup.wristbandprinter.editor.domain.ElementType;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.domain.TemplateElement;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a {@link TemplateDefinition} to ZPL. With a {@link WristbandData} the bound fields
 * carry real values; with {@link #renderTemplate} they carry {@code ${BINDING}} placeholders
 * (used for the saved snapshot).
 */
@Service
public class TemplateZplRenderer {

    private final TemplateAssetService assetService;

    public TemplateZplRenderer(TemplateAssetService assetService) {
        this.assetService = assetService;
    }

    /** Render with real data substituted into bound fields. */
    public String render(TemplateDefinition def, WristbandData data) {
        return renderWith(def, toMap(data));
    }

    /** Render with {@code ${BINDING}} placeholders instead of data (for the saved snapshot). */
    public String renderTemplate(TemplateDefinition def) {
        return renderWith(def, null);
    }

    private String renderWith(TemplateDefinition def, Map<DataBinding, String> data) {
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA");
        zpl.append("^PW").append(def.canvas().widthDots());
        zpl.append("^LL").append(def.canvas().lengthDots());
        zpl.append("^CI28");

        for (TemplateElement el : def.elements()) {
            switch (el.type()) {
                case TEXT, STATIC_TEXT -> appendText(zpl, el, data);
                case BARCODE -> appendBarcode(zpl, el, data);
                case IMAGE -> appendImage(zpl, el);
                case SHAPE -> appendShape(zpl, el);
            }
        }

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendText(StringBuilder zpl, TemplateElement el, Map<DataBinding, String> data) {
        int size = el.fontSize() == null ? 24 : el.fontSize();
        String font = el.font() == null ? "0" : el.font();
        String text = el.type() == ElementType.STATIC_TEXT
            ? sanitize(el.value())
            : valueFor(el.binding(), data);
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^A%s%s,%d,%d", font, orientation(el.rotation()), size, size));
        zpl.append(String.format("^FD%s^FS", text));
    }

    private void appendBarcode(StringBuilder zpl, TemplateElement el, Map<DataBinding, String> data) {
        String hri = Boolean.TRUE.equals(el.showHumanReadable()) ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^BC%s,%d,%s,N,N", orientation(el.rotation()), el.heightDots(), hri));
        zpl.append(String.format("^FD%s^FS", valueFor(el.binding(), data)));
    }

    private void appendImage(StringBuilder zpl, TemplateElement el) {
        if (el.assetId() == null) {
            return;
        }
        String gf = assetService.gfCommand(el.assetId(), el.widthDots(), el.heightDots(), el.rotation());
        if (!gf.isEmpty()) {
            zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
            zpl.append(gf);
        }
    }

    private void appendShape(StringBuilder zpl, TemplateElement el) {
        int thickness = el.thicknessDots() == null ? 1 : el.thicknessDots();
        zpl.append(String.format("^FO%d,%d", el.x(), el.y()));
        zpl.append(String.format("^GB%d,%d,%d^FS", el.widthDots(), el.heightDots(), thickness));
    }

    /** Bound value: real data when present, otherwise a ${BINDING} placeholder. */
    private String valueFor(DataBinding binding, Map<DataBinding, String> data) {
        if (binding == null) {
            return "";
        }
        if (data == null) {
            return "${" + binding.name() + "}";
        }
        return sanitize(data.getOrDefault(binding, ""));
    }

    private Map<DataBinding, String> toMap(WristbandData d) {
        Map<DataBinding, String> m = new EnumMap<>(DataBinding.class);
        m.put(DataBinding.EVENT_NAME, d.eventName());
        m.put(DataBinding.FIRST_NAME, d.firstName());
        m.put(DataBinding.LAST_NAME, d.lastName());
        m.put(DataBinding.FULL_NAME, d.firstName() + " " + d.lastName());
        m.put(DataBinding.ASSOCIATION_NAME, d.associationName());
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
