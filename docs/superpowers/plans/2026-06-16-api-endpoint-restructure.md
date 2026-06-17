# API Endpoint Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the split crew/permit print & preview endpoints into one polymorphic set, rename the template/asset endpoints, and switch the `wristbandType` wire value to lowercase — a hard cut with no backward-compat aliases.

**Architecture:** A single `POST /api/wristbands/print` (plus `/preview/zpl` and `/preview/image`) accepts the existing sealed `PrintableRequest`, with Jackson selecting CREW vs PERMIT from a lowercase `wristbandType` discriminator. The service layer (`PrintQueueService`, `WristbandZplResolver`) is unchanged. Templates move to `/api/wristband-templates` and assets to a new `/api/wristband-assets` controller. The `WristbandType` enum keeps uppercase Java constants but serializes/parses lowercase via `@JsonValue`/`@JsonCreator`, which also flips the jobs response and the jobs-UI type filter to lowercase.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (web, security), Jackson, springdoc-openapi, JUnit 5 + Spring MockMvc + Testcontainers; vanilla JS front-end (no build step).

**Spec:** [docs/superpowers/specs/2026-06-16-api-endpoint-restructure-design.md](../specs/2026-06-16-api-endpoint-restructure-design.md)

**Conventions for every task:** Tests live under `src/test/java` mirroring the package path. Run the full Java suite with `./mvnw test` (needs Docker for Testcontainers); run a single class with `./mvnw test -Dtest=ClassName`. Front-end has no automated tests — verify those tasks in the browser preview. Commit after each task.

---

## File Structure

**Created:**
- `src/test/java/com/stup/wristbandprinter/domain/WristbandTypeJsonTest.java` — enum JSON round-trip (Task 1)
- `src/test/java/com/stup/wristbandprinter/domain/PrintableRequestJsonTest.java` — polymorphic deserialization (Task 2)
- `src/main/java/com/stup/wristbandprinter/editor/controller/WristbandAssetController.java` — assets resource (Task 5)
- `src/test/java/com/stup/wristbandprinter/editor/controller/WristbandAssetControllerTest.java` — asset upload/fetch (Task 5)

**Modified:**
- `src/main/java/com/stup/wristbandprinter/domain/WristbandType.java` — lowercase wire mapping (Task 1)
- `src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java` — Jackson polymorphism (Task 2)
- `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java` — merged endpoints (Task 3)
- `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java` — unreadable-body → 400 (Task 3)
- `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java` — rename + preview consolidation (Task 4)
- `src/main/resources/static/js/editor/api.js`, `.../editor/canvas.js` — new paths (Task 6)
- `src/main/resources/static/js/jobs.js`, `src/main/resources/static/css/app.css` — lowercase type (Task 7)
- `src/main/java/com/stup/wristbandprinter/config/OpenApiConfig.java` — tag wording (Task 8)
- Tests: `WristbandControllerTest`, `WristbandIntegrationTest`, `GlobalExceptionHandlerTest`, `TemplateControllerTest` (Tasks 3–5)
- Docs: `docs/api.md`, `README.md`, `CLAUDE.md`, `docs/permit-wristband.md`, `docs/template-designer.md`, `HANDOVER.md` (Task 9)

**Deleted:**
- `src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java` (Task 3)
- `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java` (Task 3)

**Confirmed NO change needed:** `SecurityConfig.java` (whitelists only static/login/swagger paths, then `.anyRequest().authenticated()` — renamed paths stay protected automatically), `SecurityConfigTest.java` (only touches `/jobs/stream` and `/login`), `PrintJobTest.java` (asserts the Java enum, not its JSON form), JPA persistence (uses `name()`, not `@JsonValue`).

---

## Task 1: `WristbandType` lowercase wire mapping

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/WristbandType.java`
- Test (create): `src/test/java/com/stup/wristbandprinter/domain/WristbandTypeJsonTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java:63` (one casing assertion; this file is deleted in Task 3, edited here only to keep the suite green)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/domain/WristbandTypeJsonTest.java`:

```java
package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WristbandTypeJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesToLowercase() throws Exception {
        assertThat(mapper.writeValueAsString(WristbandType.CREW)).isEqualTo("\"crew\"");
        assertThat(mapper.writeValueAsString(WristbandType.PERMIT)).isEqualTo("\"permit\"");
    }

    @Test
    void deserializesLowercase() throws Exception {
        assertThat(mapper.readValue("\"crew\"", WristbandType.class)).isEqualTo(WristbandType.CREW);
        assertThat(mapper.readValue("\"permit\"", WristbandType.class)).isEqualTo(WristbandType.PERMIT);
    }

    @Test
    void deserializesAnyCaseForRobustness() throws Exception {
        assertThat(mapper.readValue("\"CREW\"", WristbandType.class)).isEqualTo(WristbandType.CREW);
        assertThat(mapper.readValue("\"Permit\"", WristbandType.class)).isEqualTo(WristbandType.PERMIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=WristbandTypeJsonTest`
Expected: FAIL — `serializesToLowercase` expects `"crew"` but gets `"CREW"`.

- [ ] **Step 3: Implement the lowercase mapping**

Replace the entire body of `src/main/java/com/stup/wristbandprinter/domain/WristbandType.java`:

```java
package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WristbandType {
    CREW,
    PERMIT;

    /** Lowercase wire form used in JSON — both the print/preview discriminator and responses. */
    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }

    /** Parse the wire form back to the enum, case-insensitively for robustness. */
    @JsonCreator
    public static WristbandType fromWire(String value) {
        return WristbandType.valueOf(value.trim().toUpperCase());
    }
}
```

- [ ] **Step 4: Update the one existing assertion that expects uppercase**

In `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java`, line 63, change:

```java
            .andExpect(jsonPath("$.wristbandType").value("PERMIT"));
```

to:

```java
            .andExpect(jsonPath("$.wristbandType").value("permit"));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=WristbandTypeJsonTest,PermitWristbandControllerTest,PrintJobTest`
Expected: PASS (PrintJobTest still green — it compares the Java enum, unaffected by `@JsonValue`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/WristbandType.java \
        src/test/java/com/stup/wristbandprinter/domain/WristbandTypeJsonTest.java \
        src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java
git commit -m "feat(domain): serialize wristbandType as lowercase on the wire"
```

---

## Task 2: Polymorphic `PrintableRequest` deserialization

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java`
- Test (create): `src/test/java/com/stup/wristbandprinter/domain/PrintableRequestJsonTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/domain/PrintableRequestJsonTest.java`:

```java
package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PrintableRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesCrewByDiscriminator() throws Exception {
        String json = """
            {"wristbandType":"crew","eventName":"E","firstName":"A","lastName":"B",
             "associationName":"C","barcodeValue":"123"}
            """;
        PrintableRequest req = mapper.readValue(json, PrintableRequest.class);
        assertThat(req).isInstanceOf(WristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.CREW);
    }

    @Test
    void deserializesPermitByDiscriminator() throws Exception {
        String json = """
            {"wristbandType":"permit","eventName":"E","permitLabel":"Elektriciteit"}
            """;
        PrintableRequest req = mapper.readValue(json, PrintableRequest.class);
        assertThat(req).isInstanceOf(PermitWristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.PERMIT);
    }

    @Test
    void serializedCrewIncludesLowercaseDiscriminator() throws Exception {
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("E"); req.setFirstName("A"); req.setLastName("B");
        req.setAssociationName("C"); req.setBarcodeValue("123");
        assertThat(mapper.writeValueAsString(req)).contains("\"wristbandType\":\"crew\"");
    }

    @Test
    void missingDiscriminatorFails() {
        String json = "{\"eventName\":\"E\",\"firstName\":\"A\"}";
        assertThatThrownBy(() -> mapper.readValue(json, PrintableRequest.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void unknownDiscriminatorFails() {
        String json = "{\"wristbandType\":\"banana\",\"eventName\":\"E\"}";
        assertThatThrownBy(() -> mapper.readValue(json, PrintableRequest.class))
            .isInstanceOf(JsonProcessingException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PrintableRequestJsonTest`
Expected: FAIL — `deserializesCrewByDiscriminator` cannot instantiate the sealed interface (`abstract type` / no type info).

- [ ] **Step 3: Add the Jackson polymorphism annotations**

In `src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java`, add the imports and annotations directly above the interface declaration. The interface body is unchanged.

Add imports after the package statement:

```java
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
```

Replace the declaration line:

```java
public sealed interface PrintableRequest permits WristbandPrintRequest, PermitWristbandPrintRequest {
```

with:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
              property = "wristbandType", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = WristbandPrintRequest.class,       name = "crew"),
    @JsonSubTypes.Type(value = PermitWristbandPrintRequest.class, name = "permit")
})
public sealed interface PrintableRequest permits WristbandPrintRequest, PermitWristbandPrintRequest {
```

> `EXISTING_PROPERTY` is deliberate: each concrete class already exposes `getWristbandType()` (serialized lowercase via the Task 1 `@JsonValue`), so Jackson reads that existing property as the type id instead of emitting a duplicate. The `@JsonSubTypes` names are lowercase to match both the incoming value and the serialized form. If `EXISTING_PROPERTY` misbehaves on your Jackson version, the test in Step 1 will catch it — fall back to `As.PROPERTY` with `visible = true` and add `@JsonIgnore` would create a duplicate, so prefer keeping `EXISTING_PROPERTY`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PrintableRequestJsonTest`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/PrintableRequest.java \
        src/test/java/com/stup/wristbandprinter/domain/PrintableRequestJsonTest.java
git commit -m "feat(domain): polymorphic PrintableRequest via wristbandType discriminator"
```

---

## Task 3: Merge crew/permit into one polymorphic controller

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java:52-85` (replace legacy redirect + crew methods)
- Delete: `src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java`
- Modify: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java` (add unreadable-body handler)
- Modify: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`
- Delete: `src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java` (sampleBody discriminator)

- [ ] **Step 1: Rewrite the controller tests first (failing)**

In `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`:

a) Replace every occurrence of `post("/api/wristbands/crew/print")` with `post("/api/wristbands/print")` (lines 66, 80, 90, 98, 312), `post("/api/wristbands/crew/preview/zpl")` with `post("/api/wristbands/preview/zpl")` (line 118), and `post("/api/wristbands/crew/preview/image")` with `post("/api/wristbands/preview/image")` (lines 132, 146, 163, 186, 201).

b) In the raw-JSON body test `print_returns400_whenFieldMissing` (line 77-79), add the discriminator so the body deserializes to a crew request and then fails *validation* (not type resolution). Change the body to:

```java
        String body = """
            {"wristbandType":"crew","firstName":"Jan","lastName":"Janssens","clubName":"STUP vzw","barcodeValue":"123"}
            """;
```

c) Replace the redirect test `crewPrint_oldUrl_redirectsTo308` (lines 105-112) with two hard-cut 404 tests:

```java
    @Test
    void oldCrewPrintUrl_returns404() throws Exception {
        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void oldPermitPrintUrl_returns404() throws Exception {
        mockMvc.perform(post("/api/wristbands/permit/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void print_returns400_whenWristbandTypeMissing() throws Exception {
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventName\":\"E\",\"firstName\":\"A\"}"))
            .andExpect(status().isBadRequest());
    }
```

> NOTE: a `@WebMvcTest(WristbandController.class)` will return 404 for the old `/crew/**` and `/permit/**` paths once those mappings are gone, since `WristbandController` no longer declares them and `PermitWristbandController` is not loaded by this slice.

d) Migrate the permit cases. Add these tests to `WristbandControllerTest` (it already `@MockitoBean`s `PreviewColorService` and sets `wristband.stock-colors[2]` for the existing crew preview-image test, so the stock-color path works). Add a helper and tests:

```java
    @Test
    void permitPrint_returns202_andLowercaseType() throws Exception {
        UUID jobId = UUID.randomUUID();
        PermitWristbandPrintRequest req = samplePermitRequest();
        PrintJob job = new PrintJob(jobId, req, null, null);
        when(printQueueService.enqueue(any())).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.wristbandType").value("permit"));
    }

    @Test
    void permitPrint_returns400_whenPermitLabelMissing() throws Exception {
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wristbandType\":\"permit\",\"eventName\":\"Pukkelpop 2026\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.permitLabel").exists());
    }

    @Test
    void permitPreviewImage_withStockColor_tintsPng() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1, 2, 3});
        when(previewColorService.tint(any(), any())).thenReturn(new byte[]{4, 5, 6});

        PermitWristbandPrintRequest req = samplePermitRequest();
        req.setStockColorCode(2);

        mockMvc.perform(post("/api/wristbands/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());

        verify(previewColorService).tint(eq(new byte[]{1, 2, 3}), eq("#800080"));
    }

    private PermitWristbandPrintRequest samplePermitRequest() {
        PermitWristbandPrintRequest r = new PermitWristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setPermitLabel("ELEKTRICITEIT");
        return r;
    }
```

> If `WristbandControllerTest` does not already import `eq`/`verify`, add `import static org.mockito.ArgumentMatchers.eq;` and `import static org.mockito.Mockito.*;` and `import com.stup.wristbandprinter.domain.PermitWristbandPrintRequest;` (covered by the existing `domain.*` import). Confirm `wristband.stock-colors[2]=#800080` is present in this class's `@TestPropertySource`; if the existing crew stock-color test uses a different code/hex, reuse those exact values.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=WristbandControllerTest`
Expected: FAIL/compile-error — the new endpoints don't exist yet; old paths still mapped.

- [ ] **Step 3: Merge the endpoints in `WristbandController`**

In `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`, replace lines 52–85 (the `// ── 308 alias …` comment through the end of `crewPreviewImage`) with:

```java
    // ── Print & preview (crew + permit; type chosen by the wristbandType discriminator) ──

    @PostMapping("/print")
    @Operation(summary = "Enqueue a wristband print job (crew or permit)", tags = {"Wristbands"})
    public ResponseEntity<PrintJobResponse> print(@Valid @RequestBody PrintableRequest request) {
        PrintJob job = printQueueService.enqueue(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toResponse());
    }

    @PostMapping(value = "/preview/zpl", produces = "text/plain;charset=UTF-8")
    @Operation(summary = "Generate ZPL for a wristband (crew or permit) as plain text", tags = {"Wristbands"})
    public ResponseEntity<String> previewZpl(@Valid @RequestBody PrintableRequest request) {
        return ResponseEntity.ok(wristbandZplResolver.resolve(request));
    }

    @PostMapping(value = "/preview/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a wristband (crew or permit) via Labelary", tags = {"Wristbands"})
    public ResponseEntity<byte[]> previewImage(@Valid @RequestBody PrintableRequest request) {
        String zpl = wristbandZplResolver.resolve(request);
        byte[] png = labelaryPreviewService.renderPreview(zpl);
        byte[] out = applyStockColor(png, request.getStockColorCode());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out);
    }
```

> The existing `applyStockColor(byte[], Integer)` private helper in this controller (used by the old crew preview-image method) is kept as-is and now serves both variants. No import changes are needed — `PrintableRequest` is covered by the existing `domain.*` import.

- [ ] **Step 4: Delete `PermitWristbandController` and its test**

```bash
git rm src/main/java/com/stup/wristbandprinter/controller/PermitWristbandController.java \
       src/test/java/com/stup/wristbandprinter/controller/PermitWristbandControllerTest.java
```

- [ ] **Step 5: Add the unreadable-body → 400 handler**

In `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`, add this import near the other Spring imports:

```java
import org.springframework.http.converter.HttpMessageNotReadableException;
```

and add this handler method just above the catch-all `@ExceptionHandler(Exception.class)` (around line 107). Match the existing `{ status, error, message }` body shape used by the other handlers:

```java
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
            "status", 400,
            "error", "Bad Request",
            "message", "Malformed request body or unknown wristbandType (expected \"crew\" or \"permit\")."
        ));
    }
```

> Without this, a missing/unknown `wristbandType` raises `HttpMessageNotReadableException`, which the catch-all maps to 500. This handler makes it the correct 400.

- [ ] **Step 6: Update `GlobalExceptionHandlerTest` paths**

In `src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java`:
- Replace `post("/api/wristbands/crew/preview/zpl")` → `post("/api/wristbands/preview/zpl")` (lines 56, 149).
- Replace `post("/api/wristbands/crew/print")` → `post("/api/wristbands/print")` (lines 81, 105, 130).
- For the method-not-allowed test (line 140-141, `get("/api/wristbands/crew/print")` expecting 405), change the comment and path to `get("/api/wristbands/print")` — GET on the POST-only endpoint still yields 405.
- For any of these that send a raw JSON string body intended to be a *valid* crew request (so the test exercises a downstream error, not type resolution), add `"wristbandType":"crew"` as the first field. For tests deliberately sending malformed/empty bodies to assert error mapping, leave them — they now correctly produce 400 via the new handler; adjust the expected status to `isBadRequest()` if any previously expected 500.

- [ ] **Step 7: Update the integration-test body discriminator**

In `src/test/java/com/stup/wristbandprinter/WristbandIntegrationTest.java`, the tests already POST to `url("/api/wristbands/print")`. Find the `sampleBody()` helper (builds the crew JSON body) and add `"wristbandType": "crew"` as the first property so it deserializes against the now-real polymorphic endpoint. If `sampleBody()` is a Java text block, the result should look like:

```java
        return """
            {
              "wristbandType": "crew",
              "eventName": "...",
              ... (existing fields unchanged) ...
            }
            """;
```

> If any other body builder in this file (e.g. for a permit scenario) exists, give it `"wristbandType": "permit"`.

- [ ] **Step 8: Run the affected tests**

Run: `./mvnw test -Dtest=WristbandControllerTest,GlobalExceptionHandlerTest,WristbandIntegrationTest`
Expected: PASS. (WristbandIntegrationTest needs Docker.)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(api): single polymorphic print/preview endpoint; remove crew/permit split"
```

---

## Task 4: Rename templates + consolidate preview

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java:27` (base path), `:76-93` (preview methods)
- Modify: `src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java`

- [ ] **Step 1: Rewrite the template tests first (failing)**

In `src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java`:

a) Replace every `"/api/templates"` literal with `"/api/wristband-templates"` (lines 50, 60, 69, 79, 87, 98, 109, 120, 128, 138, 147). Leave line 160 (`/api/templates/assets`) for Task 5.

b) Rewrite the two preview tests (lines 132-149) to use POST with no body (sample data) instead of GET:

```java
    @Test
    void preview_returnsPngWithSampleData() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), eq("red")))
            .thenReturn(Optional.of(new byte[]{1, 2, 3}));

        mockMvc.perform(post("/api/wristband-templates/" + id + "/preview?color=red")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void preview_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/wristband-templates/" + id + "/preview")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }
```

> Ensure `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;` is present (it likely is, alongside `get`).

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=TemplateControllerTest`
Expected: FAIL — base path still `/api/templates`; GET preview still mapped, POST preview requires a body.

- [ ] **Step 3: Update the controller base path**

In `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java`, line 27, change:

```java
@RequestMapping("/api/templates")
```

to:

```java
@RequestMapping("/api/wristband-templates")
```

- [ ] **Step 4: Consolidate the preview endpoints**

Replace the two preview methods (lines 76-93, the `@GetMapping("/{id}/preview")` `preview` method and the `@PostMapping("/{id}/preview")` `previewWithData` method) with a single optional-body POST:

```java
    @PostMapping(value = "/{id}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a PNG preview of a template; uses sample data when no body is supplied")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id,
                                          @RequestParam(required = false) String color,
                                          @RequestBody(required = false) WristbandData data) {
        return templateService.renderPreview(id, data, color)
            .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

> A POST with no body and `@RequestBody(required = false)` yields `data == null`, which `renderPreview` already treats as "use sample data" — exactly the old GET behavior.

- [ ] **Step 5: Run to verify pass**

Run: `./mvnw test -Dtest=TemplateControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java \
        src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java
git commit -m "feat(templates): rename to /api/wristband-templates; fold GET preview into optional-body POST"
```

---

## Task 5: Extract `WristbandAssetController`

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/editor/controller/WristbandAssetController.java`
- Modify: `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java:95-108` (remove asset methods)
- Create: `src/test/java/com/stup/wristbandprinter/editor/controller/WristbandAssetControllerTest.java`
- Modify: `src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java:151-163` (remove the asset test moved to the new class)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/stup/wristbandprinter/editor/controller/WristbandAssetControllerTest.java`:

```java
package com.stup.wristbandprinter.editor.controller;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.service.TemplateService;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandAssetController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class WristbandAssetControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TemplateService templateService;

    private static final String API_KEY = "test-key";

    @Test
    void uploadAsset_returns201WithAssetId() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.storeAsset(eq("logo.png"), any()))
            .thenReturn(new AssetResponse(assetId, "logo.png", 40, 20));

        var file = new MockMultipartFile("file", "logo.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/wristband-assets").file(file).header("X-API-Key", API_KEY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(assetId.toString()));
    }

    @Test
    void getAsset_returnsPng() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.rawAsset(eq(assetId))).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/wristband-assets/" + assetId).header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void getAsset_returns404_whenMissing() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.rawAsset(eq(assetId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/wristband-assets/" + assetId).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=WristbandAssetControllerTest`
Expected: FAIL — `WristbandAssetController` does not exist (compile error).

- [ ] **Step 3: Create the controller**

Create `src/main/java/com/stup/wristbandprinter/editor/controller/WristbandAssetController.java`:

```java
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
```

- [ ] **Step 4: Remove the asset methods from `TemplateController`**

In `src/main/java/com/stup/wristbandprinter/editor/controller/TemplateController.java`, delete the `uploadAsset` and `getAsset` methods (lines 95-108). After deletion the class's last method is the `preview` POST from Task 4. Remove now-unused imports if the compiler flags them (`MultipartFile`, `IOException`, `AssetResponse` — `AssetResponse` is only used by the deleted method; `MediaType` and `UUID` are still used by other methods, keep them).

- [ ] **Step 5: Remove the moved asset test from `TemplateControllerTest`**

In `src/test/java/com/stup/wristbandprinter/editor/controller/TemplateControllerTest.java`, delete the `uploadAsset_returns201WithAssetId` test (lines 151-163) — it now lives in `WristbandAssetControllerTest`. Remove the `multipart` static import if unused.

- [ ] **Step 6: Run to verify pass**

Run: `./mvnw test -Dtest=WristbandAssetControllerTest,TemplateControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(assets): extract WristbandAssetController at /api/wristband-assets"
```

---

## Task 6: Front-end — template editor paths

**Files:**
- Modify: `src/main/resources/static/js/editor/api.js`
- Modify: `src/main/resources/static/js/editor/canvas.js:165`

No automated tests (build-free vanilla JS) — verified via the browser preview in Step 4.

- [ ] **Step 1: Update `editor/api.js`**

Apply these exact edits to `src/main/resources/static/js/editor/api.js`:

- Line 1 comment: `// Thin wrappers over the /api/templates* endpoints.` → `// Thin wrappers over the /api/wristband-templates and /api/wristband-assets endpoints.`
- Line 9: `fetch('/api/templates')` → `fetch('/api/wristband-templates')`
- Line 14: `fetch('/api/templates/' + id)` → `fetch('/api/wristband-templates/' + id)`
- Line 20: `fetch('/api/templates', {` → `fetch('/api/wristband-templates', {`
- Line 28: `fetch('/api/templates/' + id, {` → `fetch('/api/wristband-templates/' + id, {`
- Lines 36-41 (`previewPng`): switch GET to a bodyless POST and update the path:

```javascript
// Live preview using the template's sample data; returns an object URL for an <img>.
export async function previewPng(id, color) {
  const url = '/api/wristband-templates/' + id + '/preview' + (color ? '?color=' + encodeURIComponent(color) : '');
  const res = guard(await fetch(url, { method: 'POST' }));
  if (!res.ok) throw new Error('preview failed');
  return URL.createObjectURL(await res.blob());
}
```

- Lines 43-50 (`previewPngWithData`): update only the path:

```javascript
export async function previewPngWithData(id, color, data) {
  const url = '/api/wristband-templates/' + id + '/preview' + (color ? '?color=' + encodeURIComponent(color) : '');
  const res = guard(await fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  }));
  if (!res.ok) throw new Error('preview failed');
  return URL.createObjectURL(await res.blob());
}
```

- Line 55 (`uploadAsset`): `fetch('/api/templates/assets', { method: 'POST', body: fd })` → `fetch('/api/wristband-assets', { method: 'POST', body: fd })`

- [ ] **Step 2: Update `editor/canvas.js`**

In `src/main/resources/static/js/editor/canvas.js`, line 165:

```javascript
  img.src = '/api/templates/assets/' + assetId;
```

→

```javascript
  img.src = '/api/wristband-assets/' + assetId;
```

- [ ] **Step 3: Sanity-check for stragglers**

Run: `grep -rn "/api/templates" src/main/resources/static/js`
Expected: no matches.

- [ ] **Step 4: Verify in the browser preview**

Start the local cluster (`docker compose -f docker-compose.local-cluster.yml up --build -d`) or the preview server, open the template editor, and confirm: the template list loads, a template preview renders (POST), and uploading a logo then placing it on the canvas works (asset GET). Check the browser console/network for 404s on `/api/wristband-templates` and `/api/wristband-assets`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/editor/api.js src/main/resources/static/js/editor/canvas.js
git commit -m "feat(editor): point front-end at /api/wristband-templates and /api/wristband-assets"
```

---

## Task 7: Front-end — lowercase wristbandType in jobs UI

**Files:**
- Modify: `src/main/resources/static/js/jobs.js:15-16`
- Modify: `src/main/resources/static/css/app.css:140-141`

The jobs response now sends `wristbandType` lowercase (Task 1), so the UI constants and CSS classes must follow.

- [ ] **Step 1: Update the JS constants**

In `src/main/resources/static/js/jobs.js`, lines 15-16:

```javascript
const TYPES = ['CREW', 'PERMIT'];
const TYPE_LABELS = { CREW: 'Crew', PERMIT: 'Permit' };
```

→

```javascript
const TYPES = ['crew', 'permit'];
const TYPE_LABELS = { crew: 'Crew', permit: 'Permit' };
```

> `typeBadge` (line 239-242), the filter (`j.wristbandType === typeFilter`, line 195), and the counts (line 257) all key off the value directly, so they work unchanged once `TYPES`/`TYPE_LABELS` use the lowercase keys. The badge CSS class becomes `badge crew` / `badge permit` — handled next.

- [ ] **Step 2: Update the badge CSS**

In `src/main/resources/static/css/app.css`, lines 140-141:

```css
.badge.CREW      { background: #3f6cd6; }
.badge.PERMIT    { background: #0c9b8a; }
```

→

```css
.badge.crew      { background: #3f6cd6; }
.badge.permit    { background: #0c9b8a; }
```

- [ ] **Step 3: Sanity-check for stragglers**

Run: `grep -rn "CREW\|PERMIT" src/main/resources/static`
Expected: no matches (all type references are lowercase now).

- [ ] **Step 4: Verify in the browser preview**

Open `jobs.html` with at least one crew and one permit job present. Confirm: the Type column shows correctly-coloured "Crew"/"Permit" badges, the Type filter dropdown lists both with counts and filters correctly, and the detail drawer type badge renders. Submit a print of each type and confirm the live SSE row shows the right badge.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/jobs.js src/main/resources/static/css/app.css
git commit -m "feat(jobs-ui): use lowercase wristbandType for badges and type filter"
```

---

## Task 8: Swagger / OpenAPI

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/config/OpenApiConfig.java`

The `@Operation` summaries were set on the merged endpoints in Task 3; springdoc derives the polymorphic request schema (oneOf + `wristbandType` discriminator) automatically from the `@JsonTypeInfo`/`@JsonSubTypes` added in Task 2. This task only refreshes the human-facing tag/group wording and verifies the rendered spec.

- [ ] **Step 1: Refresh the tag wording**

In `src/main/java/com/stup/wristbandprinter/config/OpenApiConfig.java`, update the class Javadoc and the `Wristbands` tag description to reflect the single endpoint. Change the `Wristbands` tag (lines 29-30):

```java
        @Tag(name = "Wristbands",
             description = "Print and preview crew and permit wristbands"),
```

to:

```java
        @Tag(name = "Wristbands",
             description = "Print and preview wristbands (crew or permit, chosen by the wristbandType field)"),
```

Also adjust the Javadoc block (lines 9-22) wording from "crew + permit print/preview" / "the crew and permit endpoints land in the same Wristbands group" to describe the single polymorphic endpoint set. This is documentation-only; the tag set is otherwise unchanged.

- [ ] **Step 2: Build and verify the generated spec**

Run: `./mvnw -q -DskipTests package` then start the app (local profile) and fetch the spec:

```bash
curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'
```

Expected: keys include `/api/wristbands/print`, `/api/wristbands/preview/zpl`, `/api/wristbands/preview/image`, `/api/wristband-templates`, `/api/wristband-templates/{id}/preview`, `/api/wristband-assets`, `/api/wristband-assets/{id}`; and NOT `/api/wristbands/crew/print`, `/api/wristbands/permit/print`, or `/api/templates`.

```bash
curl -s http://localhost:8080/v3/api-docs | jq '.paths."/api/wristbands/print".post.requestBody.content."application/json".schema'
```

Expected: a `oneOf`/`discriminator` referencing `WristbandPrintRequest` and `PermitWristbandPrintRequest` with `propertyName: "wristbandType"`. (Open `/swagger-ui.html` and confirm "Try it out" on `POST /api/wristbands/print` lets you pick crew/permit.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/OpenApiConfig.java
git commit -m "docs(openapi): describe the single polymorphic wristband endpoint"
```

---

## Task 9: Documentation

**Files:**
- Modify: `docs/api.md`, `README.md`, `CLAUDE.md`, `docs/permit-wristband.md`, `docs/template-designer.md`, `HANDOVER.md`

- [ ] **Step 1: Update `docs/api.md` (primary API reference)**

Replace the crew/permit endpoint sections with the merged set. For each of print, preview/zpl, preview/image, document `POST /api/wristbands/{print,preview/zpl,preview/image}` with the `wristbandType` discriminator and a crew **and** permit JSON example (lowercase `"wristbandType": "crew"`/`"permit"`). Rename the template section endpoints to `/api/wristband-templates` and the assets to `/api/wristband-assets`; note `GET /{id}/preview` is gone and `POST /{id}/preview` takes an optional body. State plainly that the old `/crew/*`, `/permit/*`, legacy `/print`, and `/api/templates*` paths are **removed** (hard cut).

- [ ] **Step 2: Update `README.md`**

Fix any quickstart/curl examples that reference `/api/wristbands/crew/print`, `/api/wristbands/permit/print`, the legacy `/print` redirect, or `/api/templates`. Each crew curl gains `"wristbandType": "crew"`; template/asset URLs are renamed.

Run to find them: `grep -rn "crew/print\|permit/print\|api/wristbands/print\|/api/templates" README.md docs`

- [ ] **Step 3: Update `CLAUDE.md`**

In the "Request flow (print)" section, replace step 1's description of `/api/wristbands/crew/print` (crew) / `/api/wristbands/permit/print` (permit) and the 308-redirect note with: a single `POST /api/wristbands/print` carrying a `wristbandType` (`crew`/`permit`) discriminator; previews via `POST /api/wristbands/preview/{zpl,image}`. Remove the "Crew URL restructure" and legacy-redirect bullets in "Important business rules" (no longer true). Update the jobs-UI and template-designer references that mention `/api/templates` to `/api/wristband-templates` / `/api/wristband-assets`. Note `wristbandType` is now lowercase on the wire.

- [ ] **Step 4: Update `docs/permit-wristband.md` and `docs/template-designer.md`**

`permit-wristband.md`: change the print endpoint from `/api/wristbands/permit/print` to `/api/wristbands/print` with `"wristbandType": "permit"`; same for its preview endpoints. `template-designer.md`: update the API section (around the `## API` heading) to `/api/wristband-templates` and `/api/wristband-assets`, and the single optional-body POST preview.

- [ ] **Step 5: Append a HANDOVER note**

Add a dated `## 2026-06-16 — API endpoint restructure` section to `HANDOVER.md` summarizing: single polymorphic `POST /api/wristbands/print` (+ previews) with lowercase `wristbandType`; templates/assets renamed; hard cut (Symfony must deploy new paths in lockstep); the jobs response `wristbandType` is now lowercase.

- [ ] **Step 6: Verify no stale references remain**

Run: `grep -rn "crew/print\|permit/print\|/api/templates\b\|wristbands/crew\|wristbands/permit" README.md docs CLAUDE.md HANDOVER.md`
Expected: no matches except inside `docs/superpowers/` historical specs/plans (those are point-in-time records — leave them).

- [ ] **Step 7: Commit**

```bash
git add docs README.md CLAUDE.md HANDOVER.md
git commit -m "docs: update API references for the endpoint restructure"
```

---

## Task 10: Full verification

- [ ] **Step 1: Run the entire test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all tests green (needs Docker for Testcontainers). Pay attention to `WorkerProfileContextTest` (profile guards unchanged) and the integration test.

- [ ] **Step 2: Grep for any missed old paths in production code**

Run:
```bash
grep -rn "crew/print\|permit/print\|/api/templates\b\|/api/wristbands/print.*308\|crew/preview\|permit/preview" src/main
```
Expected: no matches.

- [ ] **Step 3: Refresh the graphify graph**

Run: `graphify update .`
Expected: completes (AST-only, no API cost).

- [ ] **Step 4: Final review commit (if anything changed)**

```bash
git add -A
git commit -m "chore: finalize API endpoint restructure" || echo "nothing to finalize"
```

- [ ] **Step 5: Summarize for the user** — confirm the suite is green, list the new endpoints, and remind that Symfony must switch to `POST /api/wristbands/print` with the lowercase `wristbandType` field plus the renamed template/asset paths, deployed in lockstep.

---

## Self-Review

**Spec coverage:**
- §4.1 hard cut → Task 3 (404 tests), Task 4/5 (renames), Task 9 (docs).
- §4.2 `wristbandType` name → Tasks 2/3.
- §4.3 lowercase casing (request + response + enum mapper) → Task 1 (enum), Task 2/3 (request), Task 7 (UI).
- §4.4 preview consolidation → Task 4.
- §4.5 template/asset hard rename → Tasks 4, 5.
- §6.1 polymorphic body → Task 2. §6.2 enum mapper → Task 1. §6.3 controller merge → Task 3. §6.4 security → confirmed no-op (File Structure note). §6.5 front-end → Tasks 6, 7. §6.6 Swagger → Task 8.
- §8 testing → each task is TDD; Task 10 runs the full suite. §9 affected files → all covered.

**Placeholder scan:** No "TBD"/"add error handling"/"similar to" — every code step shows full code or exact old→new strings.

**Type consistency:** `wireValue()`/`fromWire()` (Task 1) referenced consistently; `@JsonSubTypes` names `"crew"`/`"permit"` match the enum's lowercase output and the test bodies; `previewPng`/`previewPngWithData` names match `editor/api.js`; `renderPreview(id, data, color)`, `storeAsset`, `rawAsset` match the existing `TemplateService` signatures observed in the controllers.
