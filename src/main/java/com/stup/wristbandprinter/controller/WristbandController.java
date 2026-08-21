package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.exception.InvalidStockColorException;
import com.stup.wristbandprinter.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Profile("!worker")
@RestController
@RequestMapping("/api/wristbands")
@SecurityRequirement(name = "ApiKeyAuth")
public class WristbandController {

    private final PrintQueueService        printQueueService;
    private final WristbandZplResolver     wristbandZplResolver;
    private final LabelaryPreviewService   labelaryPreviewService;
    private final PrinterRegistry          printerRegistry;
    private final WristbandProperties      wristbandProperties;
    private final PreviewColorService      previewColorService;
    private final WristbandGalleryCatalog  galleryCatalog;

    public WristbandController(PrintQueueService printQueueService,
                                WristbandZplResolver wristbandZplResolver,
                                LabelaryPreviewService labelaryPreviewService,
                                PrinterRegistry printerRegistry,
                                WristbandProperties wristbandProperties,
                                PreviewColorService previewColorService,
                                WristbandGalleryCatalog galleryCatalog) {
        this.printQueueService   = printQueueService;
        this.wristbandZplResolver = wristbandZplResolver;
        this.labelaryPreviewService = labelaryPreviewService;
        this.printerRegistry     = printerRegistry;
        this.wristbandProperties  = wristbandProperties;
        this.previewColorService  = previewColorService;
        this.galleryCatalog       = galleryCatalog;
    }

    // ── Print & preview (crew + permit; type chosen by the wristbandType discriminator) ──

    @PostMapping("/print")
    @Operation(summary = "Enqueue a wristband print job (crew, permit, or freetext)", tags = {"Wristbands"})
    public ResponseEntity<PrintJobResponse> print(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "Crew",     value = WristbandRequestExamples.CREW),
                @ExampleObject(name = "Permit",   value = WristbandRequestExamples.PERMIT),
                @ExampleObject(name = "FreeText", value = WristbandRequestExamples.FREETEXT)
            }))
            PrintableRequest request) {
        PrintJob job = printQueueService.enqueue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse());
    }

    @PostMapping(value = "/preview/zpl", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "Generate ZPL for a wristband (crew, permit, or freetext) as plain text", tags = {"Wristbands"})
    public ResponseEntity<String> previewZpl(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "Crew",     value = WristbandRequestExamples.CREW),
                @ExampleObject(name = "Permit",   value = WristbandRequestExamples.PERMIT),
                @ExampleObject(name = "FreeText", value = WristbandRequestExamples.FREETEXT)
            }))
            PrintableRequest request) {
        return ResponseEntity.ok(wristbandZplResolver.resolve(request));
    }

    @PostMapping(value = "/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a wristband (crew, permit, or freetext) via Labelary", tags = {"Wristbands"})
    public ResponseEntity<byte[]> previewImage(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "Crew",     value = WristbandRequestExamples.CREW),
                @ExampleObject(name = "Permit",   value = WristbandRequestExamples.PERMIT),
                @ExampleObject(name = "FreeText", value = WristbandRequestExamples.FREETEXT)
            }))
            PrintableRequest request) {
        String zpl = wristbandZplResolver.resolve(request);
        byte[] png = labelaryPreviewService.renderPreview(zpl);
        byte[] out = applyStockColor(png, request.getStockColorCode());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out);
    }

    // ── Job management (type-agnostic) ────────────────────────────────────

    @GetMapping("/jobs")
    @Operation(summary = "List all print jobs, optionally filtered by status", tags = {"Jobs"})
    public ResponseEntity<List<PrintJobResponse>> getJobs(
            @RequestParam(required = false) PrintJobStatus status) {
        return ResponseEntity.ok(
            printQueueService.getJobs(status).stream().map(PrintJob::toResponse).toList());
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get full detail of a specific print job", tags = {"Jobs"})
    public ResponseEntity<PrintJobDetailResponse> getJob(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .map(job -> ResponseEntity.ok(job.toDetailResponse()))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{jobId}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a job's wristband as a PNG via Labelary", tags = {"Jobs"})
    public ResponseEntity<byte[]> jobPreview(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .<ResponseEntity<byte[]>>map(job -> {
                String zpl = wristbandZplResolver.resolve(job.getRequest());
                byte[] png = labelaryPreviewService.renderPreview(zpl);
                byte[] out = applyStockColor(png, job.getRequest().getStockColorCode());
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to real-time job status updates via SSE", tags = {"Jobs"})
    public SseEmitter streamJobs() {
        return printQueueService.subscribe();
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to a single job's status updates via SSE", tags = {"Jobs"})
    public ResponseEntity<SseEmitter> streamJob(@PathVariable UUID jobId) {
        SseEmitter emitter = printQueueService.subscribeToJob(jobId);
        return emitter == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(emitter);
    }

    @PostMapping("/jobs/{jobId}/reprint")
    @Operation(summary = "Reprint a previous job, optionally on a different printer and/or copy count", tags = {"Jobs"})
    public ResponseEntity<PrintJobResponse> reprint(@PathVariable UUID jobId,
                                                     @RequestParam(required = false) String printerId,
                                                     @RequestParam(required = false) Integer copies) {
        return printQueueService.getJob(jobId)
            .map(original -> {
                PrintableRequest req = original.getRequest();
                if (printerId != null && !printerId.isBlank()) {
                    req = req.withPrinterId(printerId);
                }
                if (copies != null) {
                    req = req.withCopies(copies);
                }
                PrintJob newJob = printQueueService.enqueue(req);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(newJob.toResponse());
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a pending print job", tags = {"Jobs"})
    public ResponseEntity<PrintJobResponse> cancel(@PathVariable UUID jobId) {
        PrintJob job = printQueueService.cancel(jobId);
        return job == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(job.toResponse());
    }

    @DeleteMapping("/jobs/completed")
    @Operation(summary = "Remove all DONE and FAILED jobs from the queue", tags = {"Jobs"})
    public ResponseEntity<Void> clearCompleted() {
        printQueueService.clearCompleted();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/printers")
    @Operation(summary = "List the printers this service can route to", tags = {"Printers & Gallery"})
    public ResponseEntity<List<PrinterSummaryResponse>> printers() {
        return ResponseEntity.ok(printerRegistry.snapshotAll().stream()
            .map(e -> new PrinterSummaryResponse(e.id(), e.displayName(), e.online(),
                e.hidden(), e.isDefault(), e.lastSeenAt()))
            .toList());
    }

    // ── Gallery ───────────────────────────────────────────────────────────

    @GetMapping("/gallery")
    @Operation(summary = "List all registered wristband band types with sample data for the gallery UI", tags = {"Printers & Gallery"})
    public ResponseEntity<List<WristbandGalleryEntry>> gallery() {
        return ResponseEntity.ok(galleryCatalog.entries());
    }

    // ── private helpers ───────────────────────────────────────────────────

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
