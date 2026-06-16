package com.stup.wristbandprinter.editor.controller;

import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Profile("!worker")
@RestController
@RequestMapping("/api/wristband-assets")
@Tag(name = "Templates", description = "Logo/image assets used by wristband templates")
@SecurityRequirement(name = "ApiKeyAuth")
public class WristbandAssetController {

    private final TemplateService templateService;

    public WristbandAssetController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping
    @Operation(summary = "Upload a logo image, returning its asset id")
    public ResponseEntity<AssetResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        AssetResponse response = templateService.storeAsset(file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(value = "/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Fetch a stored logo image")
    public ResponseEntity<byte[]> get(@PathVariable UUID id) {
        return templateService.rawAsset(id)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
