package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import com.stup.wristbandprinter.editor.domain.TemplateDetailResponse;
import com.stup.wristbandprinter.editor.domain.TemplateSummaryResponse;
import com.stup.wristbandprinter.editor.domain.UpsertTemplateRequest;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemplateService {

    private final WristbandTemplateRepository repository;
    private final TemplateZplRenderer renderer;
    private final LabelaryPreviewService labelaryPreviewService;
    private final PreviewColorService previewColorService;
    private final TemplateAssetService assetService;

    public TemplateService(WristbandTemplateRepository repository,
                           TemplateZplRenderer renderer,
                           LabelaryPreviewService labelaryPreviewService,
                           PreviewColorService previewColorService,
                           TemplateAssetService assetService) {
        this.repository = repository;
        this.renderer = renderer;
        this.labelaryPreviewService = labelaryPreviewService;
        this.previewColorService = previewColorService;
        this.assetService = assetService;
    }

    @Transactional
    public TemplateDetailResponse create(UpsertTemplateRequest request) {
        Instant now = Instant.now();
        WristbandTemplateEntity entity = new WristbandTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(uniqueSlug(request.name()));
        entity.setName(request.name());
        entity.setProjectType(blankToNull(request.projectType()));
        entity.setDefaultPreviewColor(previewColorOrDefault(request.defaultPreviewColor()));
        entity.setDefinition(request.definition());
        entity.setGeneratedZpl(renderer.renderTemplate(request.definition()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(false);
        return toDetail(repository.save(entity));
    }

    @Transactional
    public Optional<TemplateDetailResponse> update(UUID id, UpsertTemplateRequest request) {
        return repository.findByIdAndDeletedFalse(id).map(entity -> {
            entity.setName(request.name());
            entity.setProjectType(blankToNull(request.projectType()));
            entity.setDefaultPreviewColor(previewColorOrDefault(request.defaultPreviewColor()));
            entity.setDefinition(request.definition());
            entity.setGeneratedZpl(renderer.renderTemplate(request.definition()));
            entity.setUpdatedAt(Instant.now());
            return toDetail(repository.save(entity));
        });
    }

    @Transactional(readOnly = true)
    public List<TemplateSummaryResponse> list(String projectType) {
        List<WristbandTemplateEntity> entities = (projectType == null || projectType.isBlank())
            ? repository.findByDeletedFalseOrderByUpdatedAtDesc()
            : repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc(projectType);
        return entities.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDetailResponse> getById(UUID id) {
        return repository.findByIdAndDeletedFalse(id).map(this::toDetail);
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDetailResponse> getBySlug(String slug) {
        return repository.findBySlugAndDeletedFalse(slug).map(this::toDetail);
    }

    @Transactional
    public boolean softDelete(UUID id) {
        return repository.findByIdAndDeletedFalse(id).map(entity -> {
            entity.setDeleted(true);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return true;
        }).orElse(false);
    }

    /** Render a PNG preview of a template with the given data (or sample data when null). */
    @Transactional(readOnly = true)
    public Optional<byte[]> renderPreview(UUID id, WristbandData data, String color) {
        return repository.findByIdAndDeletedFalse(id).map(e -> {
            TemplateDefinition def = e.getDefinition();
            WristbandData effective = data != null ? data : SampleData.WRISTBAND;
            String zpl = renderer.render(def, effective);
            double w = (double) def.canvas().widthDots() / def.canvas().dpi();
            double h = (double) def.canvas().lengthDots() / def.canvas().dpi();
            int dpmm = Math.round(def.canvas().dpi() / 25.4f);
            byte[] png = labelaryPreviewService.renderPreview(zpl, w, h, dpmm);
            String effectiveColor = (color == null || color.isBlank()) ? e.getDefaultPreviewColor() : color;
            return previewColorService.tint(png, effectiveColor);
        });
    }

    @Transactional
    public AssetResponse storeAsset(String name, byte[] png) {
        return assetService.store(name, png);
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> rawAsset(UUID id) {
        return assetService.rawPng(id);
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "template";
        }
        String candidate = base;
        int suffix = 2;
        while (repository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String previewColorOrDefault(String color) {
        return (color == null || color.isBlank()) ? "white" : color.trim();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private TemplateDetailResponse toDetail(WristbandTemplateEntity e) {
        return new TemplateDetailResponse(e.getId(), e.getSlug(), e.getName(), e.getProjectType(),
            e.getDefaultPreviewColor(), e.getDefinition(), e.getGeneratedZpl(), e.getUpdatedAt());
    }

    private TemplateSummaryResponse toSummary(WristbandTemplateEntity e) {
        return new TemplateSummaryResponse(e.getId(), e.getSlug(), e.getName(),
            e.getProjectType(), e.getUpdatedAt());
    }
}
