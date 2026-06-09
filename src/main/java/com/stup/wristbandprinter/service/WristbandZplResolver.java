package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.PrintableRequest;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.editor.service.TemplateZplRenderer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Resolves the ZPL for a wristband request.
 *
 * <ul>
 *   <li>CREW without templateId → legacy fixed layout via {@link ZplGeneratorService}</li>
 *   <li>CREW with templateId → designer template via {@link TemplateZplRenderer}</li>
 *   <li>PERMIT → {@link PermitZplGeneratorService} (wired in Part 3)</li>
 * </ul>
 *
 * Shared by the print queue and every preview endpoint so "what you preview" equals "what prints".
 */
@Profile("!worker")
@Service
public class WristbandZplResolver {

    private final ZplGeneratorService zplGeneratorService;
    private final WristbandLayoutService layoutService;
    private final WristbandTemplateRepository templateRepository;
    private final TemplateZplRenderer templateRenderer;

    public WristbandZplResolver(ZplGeneratorService zplGeneratorService,
                                WristbandLayoutService layoutService,
                                WristbandTemplateRepository templateRepository,
                                TemplateZplRenderer templateRenderer) {
        this.zplGeneratorService = zplGeneratorService;
        this.layoutService = layoutService;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
    }

    /**
     * Resolve ZPL for any printable request type.
     * CREW uses the legacy layout (or template when templateId is set).
     * PERMIT routing is added in Part 3.
     */
    public String resolve(PrintableRequest request) {
        return switch (request.getWristbandType()) {
            case CREW    -> resolveCrew((WristbandPrintRequest) request);
            case PERMIT  -> throw new UnsupportedOperationException(
                "PERMIT ZPL rendering not yet wired (Part 3)");
        };
    }

    private String resolveCrew(WristbandPrintRequest request) {
        WristbandData data = layoutService.buildData(request);
        if (request.getTemplateId() == null) {
            return zplGeneratorService.generate(data);
        }
        WristbandTemplateEntity template = templateRepository
            .findByIdAndDeletedFalse(request.getTemplateId())
            .orElseThrow(() -> new IllegalStateException(
                "Template not found: " + request.getTemplateId()));
        return templateRenderer.render(template.getDefinition(), data);
    }

    /**
     * Legacy two-arg overload kept for existing preview callers.
     * Delegates to the single-arg form; the pre-built {@code data} is ignored for
     * template rendering (the template renderer rebuilds from the request).
     *
     * @deprecated Use {@link #resolve(PrintableRequest)} directly.
     */
    @Deprecated
    public String resolve(WristbandPrintRequest request, WristbandData data) {
        return resolve(request);
    }
}
