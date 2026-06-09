package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Profile("!worker")
@RestController
@RequestMapping("/api/wristbands")
@Tag(name = "Wristbands", description = "Print and preview STUP event wristbands")
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

    // ── 308 alias: old /print → /crew/print ──────────────────────────────

    @PostMapping("/print")
    @Operation(summary = "Deprecated alias — redirects 308 to /crew/print")
    public ResponseEntity<Void> printLegacyRedirect() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
            .header("Location", "/api/wristbands/crew/print")
            .build();
    }

    // ── Crew endpoints ────────────────────────────────────────────────────

    @PostMapping("/crew/print")
    @Operation(summary = "Enqueue a crew wristband print job")
    public ResponseEntity<PrintJobResponse> crewPrint(@Valid @RequestBody WristbandPrintRequest request) {
        PrintJob job = printQueueService.enqueue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse());
    }

    @PostMapping(value = "/crew/preview/zpl", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "Generate and return ZPL for a crew wristband as plain text")
    public ResponseEntity<String> crewPreviewZpl(@Valid @RequestBody WristbandPrintRequest request) {
        String zpl = wristbandZplResolver.resolve(request);
        return ResponseEntity.ok(zpl);
    }

    @PostMapping(value = "/crew/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Generate and return a rendered PNG preview of a crew wristband via Labelary")
    public ResponseEntity<byte[]> crewPreviewImage(@Valid @RequestBody WristbandPrintRequest request) {
        String zpl  = wristbandZplResolver.resolve(request);
        byte[] png  = labelaryPreviewService.renderPreview(zpl);
        byte[] out  = applyStockColor(png, request.getStockColorCode());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out);
    }

    // ── Job management (type-agnostic) ────────────────────────────────────

    @GetMapping("/jobs")
    @Operation(summary = "List all print jobs, optionally filtered by status")
    public ResponseEntity<List<PrintJobResponse>> getJobs(
            @RequestParam(required = false) PrintJobStatus status) {
        return ResponseEntity.ok(
            printQueueService.getJobs(status).stream().map(PrintJob::toResponse).toList());
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get full detail of a specific print job")
    public ResponseEntity<PrintJobDetailResponse> getJob(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .map(job -> ResponseEntity.ok(job.toDetailResponse()))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{jobId}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a job's wristband as a PNG via Labelary")
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
    @Operation(summary = "Subscribe to real-time job status updates via SSE")
    public SseEmitter streamJobs() {
        return printQueueService.subscribe();
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to a single job's status updates via SSE")
    public ResponseEntity<SseEmitter> streamJob(@PathVariable UUID jobId) {
        SseEmitter emitter = printQueueService.subscribeToJob(jobId);
        return emitter == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(emitter);
    }

    @PostMapping("/jobs/{jobId}/reprint")
    @Operation(summary = "Reprint a previous job, optionally on a different printer")
    public ResponseEntity<PrintJobResponse> reprint(@PathVariable UUID jobId,
                                                     @RequestParam(required = false) String printerId) {
        return printQueueService.getJob(jobId)
            .map(original -> {
                PrintableRequest req = original.getRequest();
                if (printerId != null && !printerId.isBlank()) {
                    req = req.withPrinterId(printerId);
                }
                PrintJob newJob = printQueueService.enqueue(req);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(newJob.toResponse());
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a pending print job")
    public ResponseEntity<PrintJobResponse> cancel(@PathVariable UUID jobId) {
        PrintJob job = printQueueService.cancel(jobId);
        return job == null
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(job.toResponse());
    }

    @DeleteMapping("/jobs/completed")
    @Operation(summary = "Remove all DONE and FAILED jobs from the queue")
    public ResponseEntity<Void> clearCompleted() {
        printQueueService.clearCompleted();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/printers")
    @Operation(summary = "List the printers this service can route to")
    public ResponseEntity<List<PrinterSummaryResponse>> printers() {
        return ResponseEntity.ok(printerRegistry.all().stream()
            .map(p -> new PrinterSummaryResponse(p.id(), p.displayName()))
            .toList());
    }

    // ── Gallery ───────────────────────────────────────────────────────────

    @GetMapping("/gallery")
    @Operation(summary = "List all registered wristband band types with sample data for the gallery UI")
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
