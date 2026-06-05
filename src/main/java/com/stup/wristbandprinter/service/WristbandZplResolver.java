package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Resolves the ZPL for a wristband request: the named template when {@code templateId} is set,
 * otherwise the legacy fixed layout. Shared by the print queue and every preview endpoint so
 * "what you preview" equals "what prints".
 */
@Profile("!worker")
@Service
public class WristbandZplResolver {

    private final ZplGeneratorService zplGeneratorService;
    private final WristbandTemplateRepository templateRepository;
    private final TemplateZplRenderer templateRenderer;

    public WristbandZplResolver(ZplGeneratorService zplGeneratorService,
                                WristbandTemplateRepository templateRepository,
                                TemplateZplRenderer templateRenderer) {
        this.zplGeneratorService = zplGeneratorService;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
    }

    public String resolve(WristbandPrintRequest request, WristbandData data) {
        if (request.getTemplateId() == null) {
            return zplGeneratorService.generate(data);
        }
        WristbandTemplateEntity template = templateRepository
            .findByIdAndDeletedFalse(request.getTemplateId())
            .orElseThrow(() -> new IllegalStateException(
                "Template not found: " + request.getTemplateId()));
        return templateRenderer.render(template.getDefinition(), data);
    }
}
