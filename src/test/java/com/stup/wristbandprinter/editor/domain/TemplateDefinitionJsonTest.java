package com.stup.wristbandprinter.editor.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDefinitionJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesAllElementTypes() throws Exception {
        TemplateElement text = new TemplateElement(
            "el-text", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        TemplateElement barcode = new TemplateElement(
            "el-bc", ElementType.BARCODE, 10, 200, 100, 400, 90,
            DataBinding.BARCODE_VALUE, null, null, null, "CODE128", false, null, null, null);
        TemplateElement shape = new TemplateElement(
            "el-box", ElementType.SHAPE, 0, 0, 203, 4, 0,
            null, null, null, null, null, null, null, ShapeType.LINE, 4);

        TemplateDefinition def = new TemplateDefinition(
            new Canvas(203, 2233, 300), List.of(text, barcode, shape));

        String json = mapper.writeValueAsString(def);
        TemplateDefinition back = mapper.readValue(json, TemplateDefinition.class);

        assertThat(back).isEqualTo(def);
        assertThat(back.canvas().widthDots()).isEqualTo(203);
        assertThat(back.elements()).hasSize(3);
        assertThat(back.elements().get(0).binding()).isEqualTo(DataBinding.FULL_NAME);
    }

    @Test
    void serializesAndDeserializesNestedGroupAndSampleText() throws Exception {
        TemplateElement first = new TemplateElement(
            "first", ElementType.TEXT, 0, 0, 28, 200, 90,
            DataBinding.FIRST_NAME, null, 28, "0", null, null, null, null, null);
        TemplateElement last = new TemplateElement(
            "last", ElementType.TEXT, 0, 0, 28, 220, 90,
            DataBinding.LAST_NAME, null, 28, "0", null, null, null, null, null);
        TemplateElement free = new TemplateElement(
            "free", ElementType.STATIC_TEXT, 0, 0, 24, 120, 0,
            null, "STAFF", 24, "0", null, null, null, null, null,
            null, null, null, null, "Crew", null);

        TemplateElement nameGroup = TemplateElement.group(
            "g-name", 0, 0, StackDirection.LENGTH, 10, CrossAlign.CENTER, java.util.List.of(first, last));
        TemplateElement outer = TemplateElement.group(
            "g-outer", 20, 40, StackDirection.LENGTH, 30, CrossAlign.START, java.util.List.of(nameGroup, free));

        TemplateDefinition def = new TemplateDefinition(new Canvas(203, 2233, 300), java.util.List.of(outer));

        String json = mapper.writeValueAsString(def);
        TemplateDefinition back = mapper.readValue(json, TemplateDefinition.class);

        assertThat(back).isEqualTo(def);
        TemplateElement o = back.elements().get(0);
        assertThat(o.type()).isEqualTo(ElementType.GROUP);
        assertThat(o.children()).hasSize(2);
        assertThat(o.children().get(0).children()).hasSize(2); // nested group preserved
        assertThat(o.children().get(1).sampleText()).isEqualTo("Crew");
        assertThat(o.children().get(0).crossAlign()).isEqualTo(CrossAlign.CENTER);
    }

    @Test
    void centerOnBand_roundTripsAndOmitsWhenNull() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        TemplateElement centered = new TemplateElement(
            "c", ElementType.TEXT, 0, 0, 28, 200, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null)
            .withCenterOnBand(true);
        String json = m.writeValueAsString(centered);
        assertThat(json).contains("\"centerOnBand\":true");

        TemplateElement plain = new TemplateElement(
            "p", ElementType.TEXT, 0, 0, 28, 200, 0,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        assertThat(m.writeValueAsString(plain)).doesNotContain("centerOnBand"); // NON_NULL

        TemplateElement back = m.readValue(json, TemplateElement.class);
        assertThat(back.centerOnBand()).isTrue();
    }
}
