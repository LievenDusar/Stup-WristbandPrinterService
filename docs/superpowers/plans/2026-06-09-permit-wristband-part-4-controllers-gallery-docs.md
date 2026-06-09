# Permit Wristband – Part 4: Controllers, Gallery & Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the crew and permit bands through their new URL scheme (`/{type}/action`), add color tinting to all preview endpoints, create the `PermitWristbandController`, build the wristband gallery admin page, and update all documentation.

**Architecture:** `WristbandController` is updated in-place: the mapping moves from `/api/wristbands` to `/api/wristbands/crew`; a `@GetMapping("/print") → 308 redirect` aliases the old URL; `WristbandLayoutService` is removed as a constructor dependency (resolver now handles layout). `PermitWristbandController` is a new `@Profile("!worker") @RestController` at `/api/wristbands/permit`. Color tinting is handled in both controllers via a shared helper that resolves `stockColorCode → hex → PreviewColorService.tint()`. The gallery catalog lists all supported band types with a fixed sample dataset and is served by a new endpoint on `WristbandController`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, existing `PreviewColorService` (editor/service), vanilla JS + HTML, JUnit 5.

**Prerequisite:** Parts 1–3 must be applied first.

---

## File map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/…/controller/WristbandController.java` | Crew URL restructure; remove layoutService dep; add color tinting; add gallery endpoint |
| Create | `src/main/java/…/controller/PermitWristbandController.java` | `/api/wristbands/permit/*` endpoints |
| Create | `src/main/java/…/service/WristbandGalleryCatalog.java` | In-memory catalog of registered band types with sample data |
| Create | `src/main/java/…/domain/WristbandGalleryEntry.java` | Gallery entry record |
| Modify | `src/test/java/…/controller/WristbandControllerTest.java` | Update to new crew URLs; remove layoutService mock; fix resolver sig |
| Create | `src/test/java/…/controller/PermitWristbandControllerTest.java` | Unit tests for permit endpoints |
| Modify | `src/main/resources/static/wristband-gallery.html` | New admin gallery page |
| Create | `src/main/resources/static/js/gallery.js` | Gallery frontend logic |
| Modify | `docs/api.md` | Updated + new endpoints |
| Modify | `docs/configuration.md` | wristband.permit.* + wristband.stock-colors sections |
| Create | `docs/permit-wristband.md` | Purpose, layout, contract, assets, ops |
| Modify | `README.md` | Verify accuracy |
| Modify | `CLAUDE.md` | Architecture + current work update (LAST STEP) |
| Modify | `HANDOVER.md` | Update (LAST STEP) |

---

## Task 14: WristbandController refactor (crew URLs + color tinting)

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Write failing tests for the new crew URLs**

Add to `WristbandControllerTest.java` (keep the existing tests too — the old `/print` must redirect):

```java
@Test
void crewPrint_newUrl_returns202() throws Exception {
    UUID jobId = UUID.randomUUID();
    PrintJob job = new PrintJob(jobId, sampleRequest());
    when(printQueueService.enqueue(any())).thenReturn(job);

    mockMvc.perform(post("/api/wristbands/crew/print")
            .header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(sampleRequest())))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(jobId.toString()));
}

@Test
void crewPrint_oldUrl_redirectsTo308() throws Exception {
    mockMvc.perform(post("/api/wristbands/print")
            .header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(sampleRequest())))
        .andExpect(status().is(308));
}

@Test
void crewPreviewZpl_newUrl_returnsZpl() throws Exception {
    when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");

    mockMvc.perform(post("/api/wristbands/crew/preview/zpl")
            .header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(sampleRequest())))
        .andExpect(status().isOk())
        .andExpect(content().string("^XA^XZ"));
}

@Test
void crewPreviewImage_withStockColor_tintsPng() throws Exception {
    when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
    when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1, 2, 3});
    when(previewColorService.tint(any(), any())).thenReturn(new byte[]{4, 5, 6});

    WristbandPrintRequest req = sampleRequest();
    req.setStockColorCode(2); // purple

    mockMvc.perform(post("/api/wristbands/crew/preview/image")
            .header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG));

    verify(previewColorService).tint(eq(new byte[]{1, 2, 3}), eq("#800080"));
}

@Test
void crewPreviewImage_withoutStockColor_doesNotTint() throws Exception {
    when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
    when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1, 2, 3});

    mockMvc.perform(post("/api/wristbands/crew/preview/image")
            .header("X-API-Key", API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(sampleRequest())))
        .andExpect(status().isOk());

    verifyNoInteractions(previewColorService);
}
```

Also add `@MockitoBean PreviewColorService previewColorService;` to the test class, and update the old `/preview/zpl` and `/preview/image` stubs from `resolve(any(), any())` to `resolve(any())`.

Import needed:
```java
import com.stup.wristbandprinter.editor.service.PreviewColorService;
```

- [ ] **Step 2: Run the new tests — expect failures**

```bash
./mvnw test -Dtest=WristbandControllerTest -q 2>&1 | tail -10
```
Expected: some tests fail (new endpoints don't exist yet, old tests use wrong resolver sig).

- [ ] **Step 3: Replace WristbandController**

```java
// src/main/java/com/stup/wristbandprinter/controller/WristbandController.java
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

    /**
     * Resolves stockColorCode to a hex string and applies it via PreviewColorService.
     * Returns the original PNG when stockColorCode is null or 1 (white = no-op).
     */
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
```

- [ ] **Step 4: Run WristbandControllerTest — expect all green**

```bash
./mvnw test -Dtest=WristbandControllerTest -q
```
Expected: all pass. Fix any remaining compilation errors first.

Note: The old tests that post to `/api/wristbands/preview/zpl` or `/preview/image` will now get a 404. Either remove those old tests (they tested the old URL) or update them to use `/crew/preview/zpl` and `/crew/preview/image`. The `print_returns202WithJobId` test should be updated to `/crew/print`; the 308 redirect test covers the old URL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/WristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
git commit -m "$(cat <<'EOF'
feat: restructure crew endpoints to /crew/* URL scheme; add stock-color tinting

Legacy /print alias redirects 308 to /crew/print.
layoutService dep removed (resolver handles layout).
All preview endpoints apply stock color tint when stockColorCode is set.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: PermitWristbandController

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java`
- Create: `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java
package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PermitWristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties({AdminProperties.class, WristbandProperties.class})
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class PermitWristbandControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean PrintQueueService       printQueueService;
    @MockitoBean WristbandZplResolver    wristbandZplResolver;
    @MockitoBean LabelaryPreviewService  labelaryPreviewService;
    @MockitoBean PreviewColorService     previewColorService;

    private static final String API_KEY = "test-key";

    @Test
    void permitPrint_returns202() throws Exception {
        UUID jobId = UUID.randomUUID();
        PermitWristbandPrintRequest req = samplePermitRequest();
        PrintJob job = new PrintJob(jobId, req, null, null);
        when(printQueueService.enqueue(any())).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/permit/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.wristbandType").value("PERMIT"));
    }

    @Test
    void permitPrint_returns400_whenPermitLabelMissing() throws Exception {
        PermitWristbandPrintRequest req = new PermitWristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        // permitLabel intentionally omitted

        mockMvc.perform(post("/api/wristbands/permit/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.permitLabel").exists());
    }

    @Test
    void permitPrint_returns401_whenApiKeyMissing() throws Exception {
        mockMvc.perform(post("/api/wristbands/permit/print")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(samplePermitRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void permitPreviewZpl_returnsZpl() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA_PERMIT^XZ");

        mockMvc.perform(post("/api/wristbands/permit/preview/zpl")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(samplePermitRequest())))
            .andExpect(status().isOk())
            .andExpect(content().string("^XA_PERMIT^XZ"));
    }

    @Test
    void permitPreviewImage_returnsPng() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA_PERMIT^XZ");
        when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/wristbands/permit/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(samplePermitRequest())))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void permitPreviewImage_withStockColor_tintsPng() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1, 2, 3});
        when(previewColorService.tint(any(), any())).thenReturn(new byte[]{4, 5, 6});

        PermitWristbandPrintRequest req = samplePermitRequest();
        req.setStockColorCode(2); // purple = #800080

        mockMvc.perform(post("/api/wristbands/permit/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
            .andExpect(status().isOk());

        verify(previewColorService).tint(eq(new byte[]{1, 2, 3}), eq("#800080"));
    }

    private PermitWristbandPrintRequest samplePermitRequest() {
        PermitWristbandPrintRequest r = new PermitWristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setPermitLabel("ELEKTRICITEIT");
        return r;
    }
}
```

- [ ] **Step 2: Run tests — expect compile error**

```bash
./mvnw test -Dtest=PermitWristbandControllerTest -q 2>&1 | tail -5
```

- [ ] **Step 3: Create PermitWristbandController**

```java
// src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java
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
```

- [ ] **Step 4: Run permit controller tests — expect all green**

```bash
./mvnw test -Dtest=PermitWristbandControllerTest -q
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java \
        src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java
git commit -m "$(cat <<'EOF'
feat: add PermitWristbandController at /api/wristbands/permit/*

Endpoints: /print, /preview/zpl, /preview/image.
Stock-color tinting applied when stockColorCode is set.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: WristbandGalleryCatalog + gallery endpoint + wristband-gallery.html

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/WristbandGalleryEntry.java`
- Create: `src/main/java/com/stup/wristbandprinter/service/WristbandGalleryCatalog.java`
- Create: `src/main/resources/static/wristband-gallery.html`
- Create: `src/main/resources/static/js/gallery.js`

The gallery endpoint (`GET /api/wristbands/gallery`) is already wired in Task 14's controller. This task creates the catalog and the frontend page.

- [ ] **Step 1: Create WristbandGalleryEntry**

```java
// src/main/java/com/stup/wristbandprinter/domain/WristbandGalleryEntry.java
package com.stup.wristbandprinter.domain;

/**
 * Describes a wristband type in the gallery.
 *
 * @param wristbandType  discriminator (CREW / PERMIT)
 * @param displayName    human-friendly name shown in the gallery tile
 * @param description    one-line description of the band's purpose
 * @param previewUrl     URL to POST to for a PNG preview (relative, no host)
 * @param samplePayload  JSON string with sample data for the preview call
 */
public record WristbandGalleryEntry(
    WristbandType wristbandType,
    String displayName,
    String description,
    String previewUrl,
    String samplePayload
) {}
```

- [ ] **Step 2: Create WristbandGalleryCatalog**

```java
// src/main/java/com/stup/wristbandprinter/service/WristbandGalleryCatalog.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandGalleryEntry;
import com.stup.wristbandprinter.domain.WristbandType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * In-memory catalog of all registered wristband band types.
 * Consumed by {@code GET /api/wristbands/gallery}.
 * Sample payloads use fixed demo data; no user input required.
 */
@Profile("!worker")
@Service
public class WristbandGalleryCatalog {

    private static final List<WristbandGalleryEntry> ENTRIES = List.of(

        new WristbandGalleryEntry(
            WristbandType.CREW,
            "Crew wristband",
            "Staff / volunteer band with barcode for shift scanning",
            "/api/wristbands/crew/preview/image",
            """
            {
              "eventName":       "Pukkelpop 2026",
              "firstName":       "Annechien",
              "lastName":        "Van De Wall",
              "associationName": "Chiro Sint-Christina Brustem",
              "barcodeValue":    "12345654245524789"
            }
            """
        ),

        new WristbandGalleryEntry(
            WristbandType.PERMIT,
            "Electricity permit",
            "Campsite power-box access permit",
            "/api/wristbands/permit/preview/image",
            """
            {
              "eventName":   "Pukkelpop 2026",
              "permitLabel": "ELEKTRICITEIT"
            }
            """
        ),

        new WristbandGalleryEntry(
            WristbandType.PERMIT,
            "Parking permit",
            "Vendor / VIP parking access permit",
            "/api/wristbands/permit/preview/image",
            """
            {
              "eventName":   "Pukkelpop 2026",
              "permitLabel": "PARKING"
            }
            """
        )
    );

    public List<WristbandGalleryEntry> entries() {
        return ENTRIES;
    }
}
```

- [ ] **Step 3: Create wristband-gallery.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Wristband Gallery – STUP</title>
  <link rel="stylesheet" href="/css/admin.css">
  <style>
    .gallery-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 24px;
      padding: 24px;
    }
    .gallery-tile {
      background: #fff;
      border: 1px solid #ddd;
      border-radius: 8px;
      padding: 16px;
      width: 220px;
      cursor: pointer;
      transition: box-shadow 0.15s;
    }
    .gallery-tile:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.12); }
    .gallery-tile img {
      width: 100%;
      height: auto;
      display: block;
      border: 1px solid #eee;
      border-radius: 4px;
      background: #f5f5f5;
    }
    .gallery-tile .tile-name {
      font-weight: 600;
      margin-top: 10px;
      font-size: 0.95rem;
    }
    .gallery-tile .tile-desc {
      font-size: 0.8rem;
      color: #666;
      margin-top: 4px;
    }
    .modal-overlay {
      display: none;
      position: fixed; inset: 0;
      background: rgba(0,0,0,0.55);
      z-index: 1000;
      align-items: center;
      justify-content: center;
    }
    .modal-overlay.open { display: flex; }
    .modal-box {
      background: #fff;
      border-radius: 10px;
      padding: 24px;
      max-width: 400px;
      width: 90%;
      text-align: center;
    }
    .modal-box img { max-width: 100%; border: 1px solid #ddd; border-radius: 4px; }
    .modal-close {
      margin-top: 16px;
      padding: 8px 20px;
      border: none;
      border-radius: 4px;
      background: #555;
      color: #fff;
      cursor: pointer;
    }
  </style>
</head>
<body>
  <header class="admin-header">
    <a href="/jobs.html" class="back-link">← Jobs</a>
    <h1>Wristband Gallery</h1>
  </header>

  <div class="gallery-grid" id="galleryGrid">
    <p class="loading">Loading…</p>
  </div>

  <div class="modal-overlay" id="modal">
    <div class="modal-box">
      <img id="modalImg" src="" alt="Wristband preview">
      <br>
      <button class="modal-close" id="modalClose">Close</button>
    </div>
  </div>

  <script src="/js/gallery.js"></script>
</body>
</html>
```

- [ ] **Step 4: Create gallery.js**

```javascript
// src/main/resources/static/js/gallery.js
(function () {
  'use strict';

  const API_KEY_HEADER = 'X-API-Key';

  // API key is stored in sessionStorage by jobs.html login flow;
  // fall back to prompt so gallery can be used standalone.
  function getApiKey() {
    return sessionStorage.getItem('apiKey') || localStorage.getItem('apiKey') || '';
  }

  async function fetchGallery() {
    const key = getApiKey();
    const res = await fetch('/api/wristbands/gallery', {
      headers: { [API_KEY_HEADER]: key }
    });
    if (!res.ok) throw new Error('Failed to load gallery (' + res.status + ')');
    return res.json();
  }

  async function fetchPreview(previewUrl, samplePayload) {
    const key = getApiKey();
    const res = await fetch(previewUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [API_KEY_HEADER]: key
      },
      body: samplePayload
    });
    if (!res.ok) throw new Error('Preview failed (' + res.status + ')');
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  }

  function buildTile(entry) {
    const tile = document.createElement('div');
    tile.className = 'gallery-tile';
    tile.innerHTML = `
      <img src="/img/placeholder-wristband.png" alt="${entry.displayName}" data-loaded="false">
      <div class="tile-name">${entry.displayName}</div>
      <div class="tile-desc">${entry.description}</div>
    `;
    const img = tile.querySelector('img');

    // Lazy-load the preview image
    fetchPreview(entry.previewUrl, entry.samplePayload)
      .then(url => { img.src = url; img.dataset.loaded = 'true'; })
      .catch(() => { img.alt = 'Preview unavailable'; });

    tile.addEventListener('click', () => openModal(img.src, entry.displayName));
    return tile;
  }

  function openModal(imgSrc, title) {
    const modal = document.getElementById('modal');
    const modalImg = document.getElementById('modalImg');
    modalImg.src = imgSrc;
    modalImg.alt = title;
    modal.classList.add('open');
  }

  document.getElementById('modalClose').addEventListener('click', () => {
    document.getElementById('modal').classList.remove('open');
  });
  document.getElementById('modal').addEventListener('click', (e) => {
    if (e.target === e.currentTarget) {
      e.currentTarget.classList.remove('open');
    }
  });

  async function init() {
    const grid = document.getElementById('galleryGrid');
    try {
      const entries = await fetchGallery();
      grid.innerHTML = '';
      if (entries.length === 0) {
        grid.innerHTML = '<p>No wristband types registered.</p>';
        return;
      }
      entries.forEach(e => grid.appendChild(buildTile(e)));
    } catch (err) {
      grid.innerHTML = '<p class="error">Could not load gallery: ' + err.message + '</p>';
    }
  }

  init();
}());
```

- [ ] **Step 5: Run full suite**

```bash
./mvnw test -q
```
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandGalleryEntry.java \
        src/main/java/com/stup/wristbandprinter/service/WristbandGalleryCatalog.java \
        src/main/resources/static/wristband-gallery.html \
        src/main/resources/static/js/gallery.js
git commit -m "$(cat <<'EOF'
feat: add wristband gallery page and catalog

GET /api/wristbands/gallery returns all band types with sample preview URLs.
wristband-gallery.html lazy-loads PNG previews and shows a full-size modal.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: Inline docs (api.md, configuration.md, permit-wristband.md, README.md)

- [ ] **Step 1: Read existing docs**

```bash
ls docs/
cat docs/api.md 2>/dev/null || echo "(does not exist yet)"
cat docs/configuration.md 2>/dev/null || echo "(does not exist yet)"
```

- [ ] **Step 2: Update or create docs/api.md**

Ensure it documents the following endpoints. Create the file from scratch if it doesn't exist; update it if it does:

```markdown
# API Reference

Base path: `/api/wristbands`
Authentication: `X-API-Key` header (all endpoints except `/jobs/stream`).

## Crew wristband

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/crew/print` | Enqueue a crew print job |
| POST | `/crew/preview/zpl` | Return ZPL as plain text |
| POST | `/crew/preview/image` | Return PNG preview via Labelary |
| POST | `/print` ⚠ | **Deprecated 308 alias** → `/crew/print` |

### Crew request body (`WristbandPrintRequest`)

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| eventName | string | ✅ | |
| firstName | string | ✅ | |
| lastName | string | ✅ | |
| associationName | string | ✅ | |
| barcodeValue | string | ✅ | Scanned at the event |
| templateId | UUID | ❌ | When set, renders via the named designer template |
| codeSymbology | CODE128 \| CODE39 \| QR | ❌ | Defaults to CODE128 |
| stockColorCode | integer | ❌ | 1=white (default). Preview-only tint. |
| printerId | string | ❌ | Defaults to first registered printer |

## Permit wristband

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/permit/print` | Enqueue a permit print job |
| POST | `/permit/preview/zpl` | Return ZPL as plain text |
| POST | `/permit/preview/image` | Return PNG preview via Labelary |

### Permit request body (`PermitWristbandPrintRequest`)

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| eventName | string | ✅ | Printed in block 4 |
| permitLabel | string | ✅ | e.g. `ELEKTRICITEIT`, `PARKING`. Printed as "Toelating [label]" |
| iconName | string | ❌ | Font Awesome icon name — stored, not rendered yet |
| codeValue | string | ❌ | When present, a scan code is printed in block 3 |
| codeSymbology | CODE128 \| CODE39 \| QR | ❌ | Defaults to CODE128 |
| stockColorCode | integer | ❌ | Preview-only tint |
| printerId | string | ❌ | Defaults to first registered printer |

## Jobs (type-agnostic)

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/jobs` | List jobs; optional `?status=PENDING\|PRINTING\|DONE\|FAILED\|CANCELLED` |
| GET | `/jobs/{jobId}` | Get full job detail |
| GET | `/jobs/{jobId}/preview` | PNG preview of the job's wristband |
| GET | `/jobs/stream` | SSE stream of all job status updates |
| GET | `/jobs/{jobId}/stream` | SSE stream for one job |
| POST | `/jobs/{jobId}/reprint` | Re-enqueue a job; optional `?printerId=` |
| POST | `/jobs/{jobId}/cancel` | Cancel a PENDING job |
| DELETE | `/jobs/completed` | Soft-delete all DONE/FAILED/CANCELLED jobs |

## Printers & Gallery

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/printers` | List registered printers |
| GET | `/gallery` | List all wristband types with sample preview data |
```

- [ ] **Step 3: Update docs/configuration.md**

Add a section for stock colors and permit properties. Append (or update) these sections:

```markdown
## Stock colors

`wristband.stock-colors` maps integer codes to hex values. Used by all preview
endpoints when `stockColorCode` is included in the request. ZPL is always monochrome
— the tint is applied to the PNG only by `PreviewColorService`.

```yaml
wristband:
  stock-colors:
    1: "#FFFFFF"   # white (default — no-op)
    2: "#800080"   # purple
    3: "#FFFF00"   # yellow
    4: "#0000FF"   # blue
    5: "#008000"   # green
    6: "#FF0000"   # red
```

To add more colors, append entries and redeploy. Codes are integers; there is no
reserved range — just keep 1 = white.

## Permit wristband layout

All values under `wristband.permit.*`:

| Key | Default | Description |
|-----|---------|-------------|
| `event-logo-path` | `classpath:images/stup-logo.png` | Event logo PNG path (classpath: or absolute) |
| `event-logo-side-margin-dots` | 30 | Horizontal margin for the event logo |
| `permit-text-font-size` | 74 | Font size for "Toelating [label]" line |
| `writing-line-font-size` | 45 | Font size for the dashes writing line |
| `event-name-font-size` | 45 | Font size for the event name (block 4) |
| `between-blocks` | 60 | Uniform gap between all top-level blocks (dots) |
| `writing-gap-dots` | 120 | Blank space between permit text and dashes line |
| `scan-code-height-dots` | 270 | Bar height for the optional scan code |
| `scan-code-module-width-dots` | 3 | Narrow-bar module width for the optional scan code |
| `inner-block-gap-dots` | 40 | Gap between event name and event logo in block 4 |

Calibrate by using `POST /api/wristbands/permit/preview/image` and adjusting YAML.
```

- [ ] **Step 4: Create docs/permit-wristband.md**

```markdown
# Permit Wristband

## Purpose

Permit wristbands grant campsite guests access to specific resources (e.g. electricity/power
boxes, parking areas, catering backstage). Unlike the crew wristband, they carry no personal
details and are not tied to a specific person in the STUP system — they are a physical
access token.

## Layout

The band is 300 × 3300 dots at 300 DPI (same physical stock as the crew band). All blocks
are vertically centered.

```
Block 1 – STUP logo (180° pre-rotated)
[betweenBlocks gap]
Block 2 – "Toelating [permitLabel]"  (^A0B, permitTextFontSize)
           [writingGapDots blank space]
           "- - - - - -" dashes line  (^A0B, writingLineFontSize)
[betweenBlocks gap]
Block 3 (optional) – scan code (CODE128 / CODE39 / QR)
[betweenBlocks gap, if block 3 present]
Block 4 – eventName  (^A0B, eventNameFontSize)
           [innerBlockGapDots]
           event logo (180° pre-rotated)
```

## API contract

### Enqueue
`POST /api/wristbands/permit/print`

### Preview (ZPL text)
`POST /api/wristbands/permit/preview/zpl`

### Preview (PNG image)
`POST /api/wristbands/permit/preview/image`

All three endpoints accept `PermitWristbandPrintRequest`. See [api.md](api.md) for the
full field list.

### Supported permit types

Any non-blank `permitLabel` is accepted. Current conventions:

| permitLabel | Resource |
|-------------|----------|
| `ELEKTRICITEIT` | Campsite power box |
| `PARKING` | Parking zone (add vendor / VIP suffix as needed) |

To add a new type, simply pass a new `permitLabel` — no code changes required.

## Assets

`wristband.permit.event-logo-path` points to the event-specific logo that appears in
block 4. Override this per environment or per event in your `application-prod.yml` or
via environment variable:

```yaml
wristband:
  permit:
    event-logo-path: /opt/stup/logos/pukkelpop-2026.png
```

The logo is loaded at startup. If it cannot be found, the application refuses to start.

## Ops runbook

**Change the event logo:** Update `event-logo-path` and restart the management container.

**Calibrate layout:** Use the preview endpoint, inspect the PNG, and adjust `wristband.permit.*`
values in `application-prod.yml`. No code changes needed.

**Reprint a permit:** Use `POST /api/wristbands/jobs/{jobId}/reprint` (same as crew jobs).

**iconName field:** Accepted and stored in the database but not rendered. Reserved for a
future Font Awesome icon overlay on the band. Pass any Font Awesome icon name (e.g. `bolt`,
`car`, `utensils`) and it will be persisted for future use.
```

- [ ] **Step 5: Verify README.md accuracy**

Read `README.md` and check whether the following are still accurate:
- API endpoint list (update `/print` → `/crew/print`)
- Configuration reference (add permit + stock-colors)
- Architecture overview (mention permit band and gallery page)

Make minimal targeted edits — do not rewrite the whole README.

- [ ] **Step 6: Commit docs**

```bash
git add docs/api.md docs/configuration.md docs/permit-wristband.md README.md
git commit -m "$(cat <<'EOF'
docs: update api.md, configuration.md; add permit-wristband.md

Documents all new endpoints (/crew/*, /permit/*, /gallery),
stock-color palette config, and permit wristband layout/ops guide.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: CLAUDE.md + HANDOVER.md (final step)

**Do this last** — these files describe the current state of the codebase and must reflect everything that was implemented in Parts 1–4.

- [ ] **Step 1: Update CLAUDE.md**

Read the current `CLAUDE.md`. Make these targeted updates:

1. **Architecture overview:** Add a bullet for the permit band. Update the request flow to mention `PrintableRequest` (sealed interface) and the two permitted subtypes.

2. **Important business rules:** Add:
   - "Permit bands carry no personal details — `firstName`, `lastName`, `barcodeValue` are NULL in the DB for permit jobs."
   - "Stock color is preview-only — ZPL is always monochrome; `stockColorCode` is resolved to hex and passed to `PreviewColorService.tint()` on preview endpoints only."
   - "`iconName` is stored but not rendered — reserved for Font Awesome icon overlay (future)."

3. **Folder structure:** Add `PermitWristbandController`, `PermitZplGeneratorService`, `PermitEventLogoService`, `WristbandGalleryCatalog` to the appropriate sections.

4. **Current work in progress:** Replace the previous WIP section with:
   - "The permit wristband feature is implemented (plans `2026-06-09-permit-wristband-part-1` through `-part-4`)."
   - "All plans are clean and merged."

5. **Recommended next steps:** Update to:
   1. Replace `iconName` stub with real Font Awesome → PNG rendering in `PermitZplGeneratorService`
   2. Multi-symbology barcode rendering in `TemplateZplRenderer` (CODE39/QR)
   3. Editor canvas barcode rendering so WYSIWYG matches printed output
   4. Replace the placeholder event logo with a real per-event asset

- [ ] **Step 2: Update HANDOVER.md**

Read the current `HANDOVER.md`. Update:
- Add a section for the permit wristband feature
- Document `PermitWristbandPrintRequest` fields and which are required
- Note that `iconName` is stored-only (not rendered)
- Note that the stock color palette is configured in `wristband.stock-colors` in YAML
- Note the new URL scheme (`/crew/*`, `/permit/*`) and the 308 alias for `/print`

- [ ] **Step 3: Run full test suite one final time**

```bash
./mvnw test -q
```
Expected: all green. Zero failures.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md HANDOVER.md
git commit -m "$(cat <<'EOF'
docs: update CLAUDE.md and HANDOVER.md to reflect permit wristband feature

Documents new architecture (PrintableRequest, permit band endpoints,
stock colors, gallery), updated folder structure, and WIP status.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage check:**
- ✅ `POST /crew/print` — Task 14
- ✅ `POST /crew/preview/zpl` — Task 14
- ✅ `POST /crew/preview/image` — Task 14
- ✅ `POST /print` → 308 to `/crew/print` — Task 14
- ✅ `POST /permit/print` — Task 15
- ✅ `POST /permit/preview/zpl` — Task 15
- ✅ `POST /permit/preview/image` — Task 15
- ✅ Stock color tinting on all image preview endpoints including job preview — Tasks 14 + 15
- ✅ `InvalidStockColorException` → 400 on unknown color code — Tasks 14 + 15
- ✅ `WristbandGalleryCatalog` + `GET /api/wristbands/gallery` — Task 16
- ✅ `wristband-gallery.html` lazy-load grid + modal — Task 16
- ✅ `docs/api.md` with all endpoints — Task 17
- ✅ `docs/configuration.md` with permit + stock-colors sections — Task 17
- ✅ `docs/permit-wristband.md` (new) — Task 17
- ✅ `README.md` verified — Task 17
- ✅ `CLAUDE.md` + `HANDOVER.md` updated last — Task 18

**Gaps / follow-ons:** `iconName` rendering (Font Awesome → PNG), multi-symbology in `TemplateZplRenderer`, canvas barcode rendering in the editor — all flagged as follow-on tasks.

---

## Execution handoff

All four plan files are complete:

| File | Tasks | Status |
|------|-------|--------|
| `…-part-1-foundation.md` | 1–4 | ✅ Saved |
| `…-part-2-request-persistence.md` | 5–9 | ✅ Saved |
| `…-part-3-permit-band.md` | 10–13 | ✅ Saved |
| `…-part-4-controllers-gallery-docs.md` | 14–18 | ✅ Saved |

**Two execution options:**

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
