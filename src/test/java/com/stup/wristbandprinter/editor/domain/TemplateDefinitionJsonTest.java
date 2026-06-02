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
}
