package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.domain.TemplateDetailResponse;
import com.stup.wristbandprinter.editor.domain.TemplateSummaryResponse;
import com.stup.wristbandprinter.editor.domain.UpsertTemplateRequest;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
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

    public TemplateService(WristbandTemplateRepository repository) {
        this.repository = repository;
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
        entity.setGeneratedZpl(null); // populated in Plan 2 once the renderer exists
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
