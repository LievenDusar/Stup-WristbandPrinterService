package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.editor.domain.Canvas;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WristbandZplResolverTest {

    @Mock private ZplGeneratorService zplGeneratorService;
    @Mock private WristbandTemplateRepository templateRepository;
    @Mock private TemplateZplRenderer templateRenderer;

    private WristbandZplResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WristbandZplResolver(zplGeneratorService, templateRepository, templateRenderer);
    }

    private final WristbandData data = new WristbandData("E", "F", "L", "A", "B");

    private WristbandPrintRequest request(UUID templateId) {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setAssociationName("A"); r.setBarcodeValue("B");
        r.setTemplateId(templateId);
        return r;
    }

    @Test
    void resolve_withoutTemplateId_usesLegacyGenerator() {
        when(zplGeneratorService.generate(data)).thenReturn("^XA-legacy^XZ");
        assertThat(resolver.resolve(request(null), data)).isEqualTo("^XA-legacy^XZ");
    }

    @Test
    void resolve_withTemplateId_rendersThatTemplate() {
        UUID id = UUID.randomUUID();
        WristbandTemplateEntity e = new WristbandTemplateEntity();
        TemplateDefinition def = new TemplateDefinition(new Canvas(330, 3300, 300), List.of());
        e.setDefinition(def);
        when(templateRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(e));
        when(templateRenderer.render(def, data)).thenReturn("^XA-template^XZ");

        assertThat(resolver.resolve(request(id), data)).isEqualTo("^XA-template^XZ");
    }

    @Test
    void resolve_withUnknownTemplateId_throws() {
        UUID id = UUID.randomUUID();
        when(templateRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(request(id), data))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Template not found");
    }
}
