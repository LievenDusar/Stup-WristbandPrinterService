package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/wristbands")
@Tag(name = "Wristbands", description = "Print and preview STUP event wristbands")
@SecurityRequirement(name = "ApiKeyAuth")
public class WristbandController {

    private final PrintQueueService printQueueService;
    private final WristbandLayoutService wristbandLayoutService;
    private final ZplGeneratorService zplGeneratorService;
    private final LabelaryPreviewService labelaryPreviewService;

    public WristbandController(PrintQueueService printQueueService,
                               WristbandLayoutService wristbandLayoutService,
                               ZplGeneratorService zplGeneratorService,
                               LabelaryPreviewService labelaryPreviewService) {
        this.printQueueService = printQueueService;
        this.wristbandLayoutService = wristbandLayoutService;
        this.zplGeneratorService = zplGeneratorService;
        this.labelaryPreviewService = labelaryPreviewService;
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
        String zpl = zplGeneratorService.generate(data);
        return ResponseEntity.ok(zpl);
    }

    @PostMapping(value = "/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Generate and return a rendered PNG preview via Labelary")
    public ResponseEntity<byte[]> previewImage(@Valid @RequestBody WristbandPrintRequest request) {
        WristbandData data = wristbandLayoutService.buildData(request);
        String zpl = zplGeneratorService.generate(data);
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

    @GetMapping(value = "/jobs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to real-time job status updates via SSE (no API key required)")
    @SecurityRequirements({})
    public SseEmitter streamJobs() {
        return printQueueService.subscribe();
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
}
