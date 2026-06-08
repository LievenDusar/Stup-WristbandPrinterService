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
    void render_centeredText_nonRotated_usesFieldBlockCenter() {
        TemplateElement el = new TemplateElement("t", ElementType.STATIC_TEXT, 999, 50, 20, 100, 0,
            null, "STAFF", 24, "0", null, null, null, null, null).withCenterOnBand(true);
        String zpl = renderer.render(def(el), data); // band width 203
        assertThat(zpl).contains("^FO0,50");
        assertThat(zpl).contains("^FB203,1,0,C");
        assertThat(zpl).contains("^A0N,24,24");
        assertThat(zpl).contains("^FDSTAFF^FS");
    }

    @Test
    void render_centeredText_rotated270_centersOnFontSize() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 60, 600, 270,
            DataBinding.FULL_NAME, null, 60, "0", null, null, null, null, null).withCenterOnBand(true);
        String zpl = renderer.render(def(el), data); // band width 203 → (203-60)/2 = 71
        assertThat(zpl).contains("^FO71,70");
        assertThat(zpl).contains("^A0B,60,60").contains("^FDJan Janssens^FS");
    }

    @Test
    void render_centeredText_rotated90_centersOnFontSize() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 999, 70, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null).withCenterOnBand(true);
        String zpl = renderer.render(def(el), data); // band width 203 → (203-28)/2 = 87
        assertThat(zpl).contains("^FO87,70");
        assertThat(zpl).contains("^A0R,28,28").contains("^FDJan Janssens^FS");
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
    void render_centeredShape_centersOnBandWidth() {
        // shape width 100 on a 203-wide band → x = (203-100)/2 = 51
        TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 999, 6, 100, 4, 0,
            null, null, null, null, null, null, null, ShapeType.LINE, 4).withCenterOnBand(true);
        assertThat(renderer.render(def(el), data)).contains("^FO51,6").contains("^GB100,4,4");
    }

    @Test
    void render_centeredShape_rotated90_usesHeightAsCross() {
        // rotated 90 → cross extent = heightDots (40); x = (203-40)/2 = 81
        TemplateElement el = new TemplateElement("g", ElementType.SHAPE, 999, 6, 100, 40, 90,
            null, null, null, null, null, null, null, ShapeType.LINE, 4).withCenterOnBand(true);
        assertThat(renderer.render(def(el), data)).contains("^FO81,6");
    }

    @Test
    void render_notCentered_isByteIdentical_backCompat() {
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

    private TemplateElement staticLeaf(String id, int w, int h) {
        return new TemplateElement(id, ElementType.STATIC_TEXT, 0, 0, w, h, 0,
            null, id.toUpperCase(), 20, "0", null, null, null, null, null);
    }

    @Test
    void render_lengthStack_placesChildrenWithMarginDownTheBand() {
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.LENGTH, 10,
            CrossAlign.START, List.of(staticLeaf("a", 40, 100), staticLeaf("b", 40, 50)));
        String zpl = renderer.render(def(g), data);
        assertThat(zpl).contains("^FO0,0");
        assertThat(zpl).contains("^FO0,110"); // 100 + 10 margin
    }

    @Test
    void render_widthStack_placesChildrenAcrossTheBand() {
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.WIDTH, 5,
            CrossAlign.START, List.of(staticLeaf("a", 30, 80), staticLeaf("b", 20, 80)));
        String zpl = renderer.render(def(g), data);
        assertThat(zpl).contains("^FO0,0");
        assertThat(zpl).contains("^FO35,0"); // 30 + 5 margin
    }

    @Test
    void render_crossAlignCenter_centersNarrowerChildOnCrossAxis() {
        TemplateElement g = TemplateElement.group("g", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.CENTER, List.of(staticLeaf("wide", 40, 50), staticLeaf("narrow", 20, 50)));
        String zpl = renderer.render(def(g), data);
        assertThat(zpl).contains("^FO0,0");   // wide, no offset, y=0
        assertThat(zpl).contains("^FO10,50"); // narrow, +10 cross, y=50
    }

    @Test
    void render_groupOrigin_offsetsAllChildren() {
        TemplateElement g = TemplateElement.group("g", 100, 200, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(staticLeaf("a", 40, 50)));
        assertThat(renderer.render(def(g), data)).contains("^FO100,200");
    }

    @Test
    void render_nestedGroup_flattensRecursively() {
        TemplateElement inner = TemplateElement.group("in", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(staticLeaf("a", 40, 50), staticLeaf("b", 40, 50)));
        TemplateElement outer = TemplateElement.group("out", 0, 0, StackDirection.LENGTH, 0,
            CrossAlign.START, List.of(inner, staticLeaf("c", 40, 30)));
        String zpl = renderer.render(def(outer), data);
        assertThat(zpl).contains("^FO0,0");   // a
        assertThat(zpl).contains("^FO0,50");  // b
        assertThat(zpl).contains("^FO0,100"); // c, after inner group's 100-dot height
    }

    @Test
    void render_flatDefinition_unchanged_isBackCompatible() {
        TemplateElement el = new TemplateElement("t", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        String zpl = renderer.render(def(el), data);
        assertThat(zpl).contains("^FO40,120^A0R,28,28").contains("^FDJan Janssens^FS");
    }
}
