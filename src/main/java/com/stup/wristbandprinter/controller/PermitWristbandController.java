package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.exception.InvalidStockColorException;
import com.stup.wristbandprinter.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("!worker")
@RestController
@RequestMapping("/api/wristbands/permit")
@Tag(name = "Permit Wristbands", description = "Print and preview STUP permit wristbands")
@SecurityRequirement(name = "ApiKeyAuth")
public class PermitWristbandController {

    private final PrintQueueService      printQueueService;
    private final WristbandZplResolver   wristbandZplResolver;
    private final LabelaryPreviewService labelaryPreviewService;
    private final WristbandProperties    wristbandProperties;
    private final PreviewColorService    previewColorService;

    public PermitWristbandController(PrintQueueService printQueueService,
                                      WristbandZplResolver wristbandZplResolver,
                                      LabelaryPreviewService labelaryPreviewService,
                                      WristbandProperties wristbandProperties,
                                      PreviewColorService previewColorService) {
        this.printQueueService    = printQueueService;
        this.wristbandZplResolver  = wristbandZplResolver;
        this.labelaryPreviewService = labelaryPreviewService;
        this.wristbandProperties   = wristbandProperties;
        this.previewColorService   = previewColorService;
    }

    @PostMapping("/print")
    @Operation(summary = "Enqueue a permit wristband print job")
    public ResponseEntity<PrintJobResponse> print(@Valid @RequestBody PermitWristbandPrintRequest request) {
        PrintJob job = printQueueService.enqueue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse());
    }

    @PostMapping(value = "/preview/zpl", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "Generate and return ZPL for a permit wristband as plain text")
    public ResponseEntity<String> previewZpl(@Valid @RequestBody PermitWristbandPrintRequest request) {
        String zpl = wristbandZplResolver.resolve(request);
        return ResponseEntity.ok(zpl);
    }

    @PostMapping(value = "/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Generate and return a rendered PNG preview of a permit wristband via Labelary")
    public ResponseEntity<byte[]> previewImage(@Valid @RequestBody PermitWristbandPrintRequest request) {
        String zpl  = wristbandZplResolver.resolve(request);
        byte[] png  = labelaryPreviewService.renderPreview(zpl);
        byte[] out  = applyStockColor(png, request.getStockColorCode());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out);
    }

    private byte[] applyStockColor(byte[] png, Integer stockColorCode) {
        if (stockColorCode == null) {
            return png;
        }
        String hex = wristbandProperties.getStockColors().get(stockColorCode);
        if (hex == null) {
            throw new InvalidStockColorException(
                "Unknown stock color code " + stockColorCode
                    + ". Configured codes: " + wristbandProperties.getStockColors().keySet());
        }
        return previewColorService.tint(png, hex);
    }
}
