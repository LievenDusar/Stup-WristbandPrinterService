package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.domain.*;
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

    private final PrintQueueService printQueueService;
    private final WristbandLayoutService wristbandLayoutService;
    private final WristbandZplResolver wristbandZplResolver;
    private final LabelaryPreviewService labelaryPreviewService;
    private final PrinterRegistry printerRegistry;

    public WristbandController(PrintQueueService printQueueService,
                               WristbandLayoutService wristbandLayoutService,
                               WristbandZplResolver wristbandZplResolver,
                               LabelaryPreviewService labelaryPreviewService,
                               PrinterRegistry printerRegistry) {
        this.printQueueService = printQueueService;
        this.wristbandLayoutService = wristbandLayoutService;
        this.wristbandZplResolver = wristbandZplResolver;
        this.labelaryPreviewService = labelaryPreviewService;
        this.printerRegistry = printerRegistry;
    }

    @PostMapping("/print")
    @Operation(summary = "Enqueue a wristband print job")
    public ResponseEntity<PrintJobResponse> print(@Valid @RequestBody WristbandPrintRequest request) {
        PrintJob job = printQueueService.enqueue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse());
    }

    @PostMapping(value = "/preview/zpl", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "Generate and return ZPL code as plain text")
    public ResponseEntity<String> previewZpl(@Valid @RequestBody WristbandPrintRequest request) {
        WristbandData data = wristbandLayoutService.buildData(request);
        String zpl = wristbandZplResolver.resolve(request, data);
        return ResponseEntity.ok(zpl);
    }

    @PostMapping(value = "/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Generate and return a rendered PNG preview via Labelary")
    public ResponseEntity<byte[]> previewImage(@Valid @RequestBody WristbandPrintRequest request) {
        WristbandData data = wristbandLayoutService.buildData(request);
        String zpl = wristbandZplResolver.resolve(request, data);
        byte[] png = labelaryPreviewService.renderPreview(zpl);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping("/jobs")
    @Operation(summary = "List all print jobs, optionally filtered by status")
    public ResponseEntity<List<PrintJobResponse>> getJobs(
            @RequestParam(required = false) PrintJobStatus status) {
        List<PrintJobResponse> responses = printQueueService.getJobs(status)
            .stream().map(PrintJob::toResponse).toList();
        return ResponseEntity.ok(responses);
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
                WristbandData data = wristbandLayoutService.buildData(job.getRequest());
                String zpl = wristbandZplResolver.resolve(job.getRequest(), data);
                byte[] png = labelaryPreviewService.renderPreview(zpl);
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to real-time job status updates via SSE (requires admin cookie or API key)")
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
    @Operation(summary = "Reprint a previous job using the same data")
    public ResponseEntity<PrintJobResponse> reprint(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .map(original -> {
                PrintJob newJob = printQueueService.enqueue(original.getRequest());
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
        List<PrinterSummaryResponse> list = printerRegistry.all().stream()
            .map(p -> new PrinterSummaryResponse(p.id(), p.displayName()))
            .toList();
        return ResponseEntity.ok(list);
    }
}
