package com.stup.wristbandprinter.editor.controller;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.domain.TemplateDetailResponse;
import com.stup.wristbandprinter.editor.domain.TemplateSummaryResponse;
import com.stup.wristbandprinter.editor.domain.UpsertTemplateRequest;
import com.stup.wristbandprinter.editor.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
@Tag(name = "Templates", description = "Create, manage and browse wristband templates")
@SecurityRequirement(name = "ApiKeyAuth")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping
    @Operation(summary = "Create a wristband template")
    public ResponseEntity<TemplateDetailResponse> create(@Valid @RequestBody UpsertTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing template")
    public ResponseEntity<TemplateDetailResponse> update(@PathVariable UUID id,
                                                         @Valid @RequestBody UpsertTemplateRequest request) {
        return templateService.update(id, request)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List templates (catalog), optionally filtered by project type")
    public ResponseEntity<List<TemplateSummaryResponse>> list(
            @RequestParam(required = false) String projectType) {
        return ResponseEntity.ok(templateService.list(projectType));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a template's full definition")
    public ResponseEntity<TemplateDetailResponse> get(@PathVariable UUID id) {
        return templateService.getById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a template")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return templateService.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @GetMapping(value = "/{id}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a template using sample data")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id,
                                          @RequestParam(required = false) String color) {
        return templateService.renderPreview(id, null, color)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{id}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a template using supplied data")
    public ResponseEntity<byte[]> previewWithData(@PathVariable UUID id,
                                                  @RequestParam(required = false) String color,
                                                  @RequestBody WristbandData data) {
        return templateService.renderPreview(id, data, color)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/assets")
    @Operation(summary = "Upload a logo image, returning its asset id")
    public ResponseEntity<AssetResponse> uploadAsset(@RequestParam("file") MultipartFile file) throws IOException {
        AssetResponse response = templateService.storeAsset(file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(value = "/assets/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Fetch a stored logo image")
    public ResponseEntity<byte[]> getAsset(@PathVariable UUID id) {
        return templateService.rawAsset(id)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
