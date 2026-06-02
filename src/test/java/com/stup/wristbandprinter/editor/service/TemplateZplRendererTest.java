package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateZplRendererTest {

    @Mock
    private TemplateAssetService assetService;

    private TemplateZplRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TemplateZplRenderer(assetService);
    }

    private final WristbandData data = new WristbandData(
        "Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "12345");

    private TemplateDefinition def(TemplateElement... els) {
        return new TemplateDefinition(new Canvas(203, 2233, 300), List.of(els));
    }

    @Test
    void render_wrapsWithLabelSetupAndDimensions() {
        String zpl = renderer.render(def(), data);
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).contains("^PW203").contains("^LL2233").contains("^CI28");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void render_boundText_substitutesDataAndMapsRotationToOrientation() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^FO40,120");
        assertThat(zpl).contains("^A0R,28,28");      // 90° → R
        assertThat(zpl).contains("^FDJan Janssens^FS");
    }

    @Test
    void render_staticText_usesLiteralValue() {
        TemplateElement el = new TemplateElement("s", ElementType.STATIC_TEXT, 10, 10, 20, 100, 0,
            null, "STAFF", 24, "0", null, null, null, null, null);
        assertThat(renderer.render(def(el), data)).contains("^A0N,24,24").contains("^FDSTAFF^FS");
    }

    @Test
    void render_barcode_emitsBcWithValue() {
        TemplateElement el = new TemplateElement("b", ElementType.BARCODE, 0, 0, 100, 400, 270,
            DataBinding.BARCODE_VALUE, null, null, null, "CODE128", false, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^BCB,400,N,N,N");   // 270° → B, height 400, no HRI
        assertThat(zpl).contains("^FD12345^FS");
    }

    @Test
    void render_shape_emitsGraphicBox() {
        TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 5, 6, 180, 4, 0,
            null, null, null, null, null, null, null, ShapeType.LINE, 4);
        assertThat(renderer.render(def(el), data)).contains("^FO5,6").contains("^GB180,4,4");
    }

    @Test
    void render_image_delegatesToAssetService() {
        UUID assetId = UUID.randomUUID();
        when(assetService.gfCommand(eq(assetId), eq(150), eq(80), eq(0))).thenReturn("^GFA,1,1,1,FF");
        TemplateElement el = new TemplateElement("i", ElementType.IMAGE, 12, 14, 150, 80, 0,
            null, null, null, null, null, null, assetId, null, null);
        assertThat(renderer.render(def(el), data)).contains("^FO12,14").contains("^GFA,1,1,1,FF");
    }

    @Test
    void renderTemplate_withNullData_emitsPlaceholders() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 0,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        assertThat(renderer.renderTemplate(def(el))).contains("^FD${FULL_NAME}^FS");
    }

    @Test
    void render_sanitizesCaretAndTilde() {
        WristbandData dirty = new WristbandData("E", "a^b", "c~d", "A", "1");
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 0, 0, 20, 100, 0,
            DataBinding.FULL_NAME, null, 20, "0", null, null, null, null, null);
        assertThat(renderer.render(def(el), dirty)).contains("^FDab cd^FS");
    }
}
