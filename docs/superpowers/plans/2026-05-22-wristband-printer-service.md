# Wristband Printer Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 Spring Boot API service that generates ZPL wristband labels, sends them to a Zebra printer via TCP, provides image preview via Labelary, manages an async print queue with SSE-based real-time status updates, and serves a job management UI.

**Architecture:** Pure programmatic ZPL generation — no ZPL library dependencies. The PNG logo is converted to ZPL `^GF` format once at startup (pre-rotated 180°) and cached. Print jobs are queued in a `LinkedBlockingQueue` processed by a single worker thread. Job status updates are broadcast to browser clients via Server-Sent Events. A static HTML page consumes the SSE stream and calls REST endpoints for reprint and clear actions.

**Tech Stack:** Java 21 · Spring Boot 3.4 · Maven · springdoc-openapi 2.6 · JUnit 5 · Mockito · Spring MockRestServiceServer · Docker multi-stage build

---

## File Structure

```
.
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── README.md
└── src/
    ├── main/
    │   ├── java/com/stup/wristbandprinter/
    │   │   ├── WristbandPrinterApplication.java
    │   │   ├── config/
    │   │   │   ├── PrinterProperties.java
    │   │   │   ├── WristbandProperties.java
    │   │   │   ├── LabelaryProperties.java
    │   │   │   └── SecurityConfig.java
    │   │   ├── controller/
    │   │   │   └── WristbandController.java
    │   │   ├── domain/
    │   │   │   ├── WristbandPrintRequest.java
    │   │   │   ├── WristbandData.java
    │   │   │   ├── WristbandPreviewResponse.java
    │   │   │   ├── PrintJob.java
    │   │   │   ├── PrintJobStatus.java
    │   │   │   └── PrintJobResponse.java
    │   │   ├── exception/
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   ├── PrinterUnavailableException.java
    │   │   │   ├── LogoNotFoundException.java
    │   │   │   └── LabelaryUnavailableException.java
    │   │   ├── security/
    │   │   │   └── ApiKeyAuthFilter.java
    │   │   └── service/
    │   │       ├── LogoConversionService.java
    │   │       ├── WristbandLayoutService.java
    │   │       ├── ZplGeneratorService.java
    │   │       ├── PrinterService.java
    │   │       ├── LabelaryPreviewService.java
    │   │       └── PrintQueueService.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml
    │       ├── application-prod.yml
    │       ├── images/
    │       │   └── stup-logo.png          ← place actual logo here
    │       └── static/
    │           └── jobs.html
    └── test/
        └── java/com/stup/wristbandprinter/
            ├── controller/
            │   └── WristbandControllerTest.java
            ├── exception/
            │   └── GlobalExceptionHandlerTest.java
            └── service/
                ├── LogoConversionServiceTest.java
                ├── WristbandLayoutServiceTest.java
                ├── ZplGeneratorServiceTest.java
                ├── PrinterServiceTest.java
                ├── LabelaryPreviewServiceTest.java
                └── PrintQueueServiceTest.java
```

---

## Task 1: Maven Project Scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/stup/wristbandprinter/WristbandPrinterApplication.java`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: Create the directory structure**

```bash
mkdir -p src/main/java/com/stup/wristbandprinter/{config,controller,domain,exception,security,service}
mkdir -p src/main/resources/{images,static}
mkdir -p src/test/java/com/stup/wristbandprinter/{controller,exception,service}
touch src/main/resources/images/.gitkeep
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.1</version>
        <relativePath/>
    </parent>

    <groupId>com.stup</groupId>
    <artifactId>wristband-printer-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>wristband-printer-service</name>
    <description>Zebra wristband printing API service for STUP events</description>

    <properties>
        <java.version>21</java.version>
        <springdoc.version>2.6.0</springdoc.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create `WristbandPrinterApplication.java`**

```java
package com.stup.wristbandprinter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WristbandPrinterApplication {
    public static void main(String[] args) {
        SpringApplication.run(WristbandPrinterApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `src/main/resources/application.yml`**

```yaml
server:
  port: 8080

security:
  api-key: changeme

printer:
  host: localhost
  port: 9100
  timeout-ms: 5000

wristband:
  width-dots: 203
  length-dots: 2233
  dpi: 203
  logo-path: classpath:images/stup-logo.png
  logo-side-margin-dots: 10
  margins:
    top-dots: 40
    between-logo-and-text: 150
    between-text-and-barcode: 150
    between-barcode-and-logo: 60
  text:
    font-size-event: 20
    font-size-name: 28
    font-size-association: 20
  barcode:
    type: CODE128
    height-dots: 100
    show-human-readable: true

labelary:
  base-url: http://api.labelary.com
  timeout-ms: 5000

springdoc:
  swagger-ui:
    enabled: true
  api-docs:
    enabled: true

logging:
  level:
    com.stup.wristbandprinter: INFO
```

- [ ] **Step 5: Verify the project compiles and starts**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: Application starts on port 8080. Spring Security will lock all endpoints by default (returns 401) — that is correct at this stage.

- [ ] **Step 6: Commit**

```bash
git init
git add pom.xml src/main/java/com/stup/wristbandprinter/WristbandPrinterApplication.java src/main/resources/application.yml
git commit -m "feat: scaffold Spring Boot 3.4 Maven project"
```

---

## Task 2: Configuration Properties + Profile YAMLs

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/config/PrinterProperties.java`
- Create: `src/main/java/com/stup/wristbandprinter/config/WristbandProperties.java`
- Create: `src/main/java/com/stup/wristbandprinter/config/LabelaryProperties.java`
- Create: `src/main/resources/application-local.yml`
- Create: `src/main/resources/application-prod.yml`

- [ ] **Step 1: Create `PrinterProperties.java`**

```java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "printer")
public class PrinterProperties {

    private String host = "localhost";
    private int port = 9100;
    private int timeoutMs = 5000;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
```

- [ ] **Step 2: Create `WristbandProperties.java`**

```java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wristband")
public class WristbandProperties {

    private int widthDots = 203;
    private int lengthDots = 2233;
    private int dpi = 203;
    private String logoPath = "classpath:images/stup-logo.png";
    private int logoSideMarginDots = 10;
    private Margins margins = new Margins();
    private Text text = new Text();
    private Barcode barcode = new Barcode();

    public int getWidthDots() { return widthDots; }
    public void setWidthDots(int widthDots) { this.widthDots = widthDots; }

    public int getLengthDots() { return lengthDots; }
    public void setLengthDots(int lengthDots) { this.lengthDots = lengthDots; }

    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }

    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public int getLogoSideMarginDots() { return logoSideMarginDots; }
    public void setLogoSideMarginDots(int logoSideMarginDots) { this.logoSideMarginDots = logoSideMarginDots; }

    public Margins getMargins() { return margins; }
    public void setMargins(Margins margins) { this.margins = margins; }

    public Text getText() { return text; }
    public void setText(Text text) { this.text = text; }

    public Barcode getBarcode() { return barcode; }
    public void setBarcode(Barcode barcode) { this.barcode = barcode; }

    public static class Margins {
        private int topDots = 40;
        private int betweenLogoAndText = 150;
        private int betweenTextAndBarcode = 150;
        private int betweenBarcodeAndLogo = 60;

        public int getTopDots() { return topDots; }
        public void setTopDots(int topDots) { this.topDots = topDots; }

        public int getBetweenLogoAndText() { return betweenLogoAndText; }
        public void setBetweenLogoAndText(int v) { this.betweenLogoAndText = v; }

        public int getBetweenTextAndBarcode() { return betweenTextAndBarcode; }
        public void setBetweenTextAndBarcode(int v) { this.betweenTextAndBarcode = v; }

        public int getBetweenBarcodeAndLogo() { return betweenBarcodeAndLogo; }
        public void setBetweenBarcodeAndLogo(int v) { this.betweenBarcodeAndLogo = v; }
    }

    public static class Text {
        private int fontSizeEvent = 20;
        private int fontSizeName = 28;
        private int fontSizeAssociation = 20;

        public int getFontSizeEvent() { return fontSizeEvent; }
        public void setFontSizeEvent(int v) { this.fontSizeEvent = v; }

        public int getFontSizeName() { return fontSizeName; }
        public void setFontSizeName(int v) { this.fontSizeName = v; }

        public int getFontSizeAssociation() { return fontSizeAssociation; }
        public void setFontSizeAssociation(int v) { this.fontSizeAssociation = v; }
    }

    public static class Barcode {
        private String type = "CODE128";
        private int heightDots = 100;
        private boolean showHumanReadable = true;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getHeightDots() { return heightDots; }
        public void setHeightDots(int heightDots) { this.heightDots = heightDots; }

        public boolean isShowHumanReadable() { return showHumanReadable; }
        public void setShowHumanReadable(boolean showHumanReadable) { this.showHumanReadable = showHumanReadable; }
    }
}
```

- [ ] **Step 3: Create `LabelaryProperties.java`**

```java
package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labelary")
public class LabelaryProperties {

    private String baseUrl = "http://api.labelary.com";
    private int timeoutMs = 5000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
```

- [ ] **Step 4: Create `src/main/resources/application-local.yml`**

```yaml
printer:
  host: 192.168.1.100   # replace with your test printer IP
  port: 9100
  timeout-ms: 3000

wristband:
  logo-path: classpath:images/stup-logo.png

security:
  api-key: local-dev-key

labelary:
  base-url: http://api.labelary.com
```

- [ ] **Step 5: Create `src/main/resources/application-prod.yml`**

```yaml
printer:
  host: ${PRINTER_HOST}   # set via environment variable
  port: 9100
  timeout-ms: 5000

wristband:
  logo-path: /opt/stup/images/stup-logo.png

security:
  api-key: ${SECURITY_API_KEY}   # set via environment variable

labelary:
  base-url: http://api.labelary.com
```

- [ ] **Step 6: Verify the app still starts with config scan**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: No `ConfigurationPropertiesBindingException`. Application starts cleanly.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/ src/main/resources/application-local.yml src/main/resources/application-prod.yml
git commit -m "feat: add typed configuration properties and profile YAMLs"
```

---

## Task 3: Domain Objects

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/domain/WristbandPrintRequest.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/WristbandData.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/WristbandPreviewResponse.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrintJobStatus.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrintJob.java`
- Create: `src/main/java/com/stup/wristbandprinter/domain/PrintJobResponse.java`

- [ ] **Step 1: Create `WristbandPrintRequest.java`**

```java
package com.stup.wristbandprinter.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Data required to print or preview a wristband")
public class WristbandPrintRequest {

    @NotBlank(message = "eventName must not be blank")
    @Schema(example = "Pukkelpop 2026")
    private String eventName;

    @NotBlank(message = "firstName must not be blank")
    @Schema(example = "Jan")
    private String firstName;

    @NotBlank(message = "lastName must not be blank")
    @Schema(example = "Janssens")
    private String lastName;

    @NotBlank(message = "associationName must not be blank")
    @Schema(example = "STUP vzw")
    private String associationName;

    @NotBlank(message = "barcodeValue must not be blank")
    @Schema(example = "123456789")
    private String barcodeValue;

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAssociationName() { return associationName; }
    public void setAssociationName(String associationName) { this.associationName = associationName; }

    public String getBarcodeValue() { return barcodeValue; }
    public void setBarcodeValue(String barcodeValue) { this.barcodeValue = barcodeValue; }
}
```

- [ ] **Step 2: Create `WristbandData.java`**

```java
package com.stup.wristbandprinter.domain;

public record WristbandData(
    String eventName,
    String firstName,
    String lastName,
    String associationName,
    String barcodeValue
) {}
```

- [ ] **Step 3: Create `WristbandPreviewResponse.java`**

```java
package com.stup.wristbandprinter.domain;

public record WristbandPreviewResponse(String zpl) {}
```

- [ ] **Step 4: Create `PrintJobStatus.java`**

```java
package com.stup.wristbandprinter.domain;

public enum PrintJobStatus {
    PENDING,
    PRINTING,
    DONE,
    FAILED
}
```

- [ ] **Step 5: Create `PrintJob.java`**

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public class PrintJob {

    private final UUID jobId;
    private final WristbandPrintRequest request;
    private volatile PrintJobStatus status;
    private final Instant submittedAt;
    private volatile Instant completedAt;
    private volatile String error;

    public PrintJob(UUID jobId, WristbandPrintRequest request) {
        this.jobId = jobId;
        this.request = request;
        this.status = PrintJobStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    public UUID getJobId() { return jobId; }
    public WristbandPrintRequest getRequest() { return request; }

    public PrintJobStatus getStatus() { return status; }
    public void setStatus(PrintJobStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public PrintJobResponse toResponse() {
        return new PrintJobResponse(
            jobId,
            status,
            request.getEventName(),
            submittedAt,
            completedAt,
            error
        );
    }
}
```

- [ ] **Step 6: Create `PrintJobResponse.java`**

```java
package com.stup.wristbandprinter.domain;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(
    UUID jobId,
    PrintJobStatus status,
    String eventName,
    Instant submittedAt,
    Instant completedAt,
    String error
) {}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/domain/
git commit -m "feat: add domain objects and DTOs"
```

---

## Task 4: Exceptions and Global Exception Handler

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/exception/PrinterUnavailableException.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/LogoNotFoundException.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/LabelaryUnavailableException.java`
- Create: `src/main/java/com/stup/wristbandprinter/exception/GlobalExceptionHandler.java`
- Create: `src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Create the three custom exceptions**

```java
// PrinterUnavailableException.java
package com.stup.wristbandprinter.exception;

public class PrinterUnavailableException extends RuntimeException {
    public PrinterUnavailableException(String message) { super(message); }
    public PrinterUnavailableException(String message, Throwable cause) { super(message, cause); }
}

// LogoNotFoundException.java
package com.stup.wristbandprinter.exception;

public class LogoNotFoundException extends RuntimeException {
    public LogoNotFoundException(String message) { super(message); }
    public LogoNotFoundException(String message, Throwable cause) { super(message, cause); }
}

// LabelaryUnavailableException.java
package com.stup.wristbandprinter.exception;

public class LabelaryUnavailableException extends RuntimeException {
    public LabelaryUnavailableException(String message) { super(message); }
    public LabelaryUnavailableException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: Create `GlobalExceptionHandler.java`**

```java
package com.stup.wristbandprinter.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                (a, b) -> a));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("error", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(PrinterUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handlePrinterUnavailable(PrinterUnavailableException ex) {
        log.warn("Printer unavailable: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Printer unavailable", ex.getMessage());
    }

    @ExceptionHandler(LabelaryUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLabelaryUnavailable(LabelaryUnavailableException ex) {
        log.warn("Labelary unavailable: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Labelary unavailable", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
```

- [ ] **Step 3: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/exception/GlobalExceptionHandlerTest.java
package com.stup.wristbandprinter.exception;

import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandLayoutService;
import com.stup.wristbandprinter.service.ZplGeneratorService;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PrintQueueService printQueueService;
    @MockBean private WristbandLayoutService wristbandLayoutService;
    @MockBean private ZplGeneratorService zplGeneratorService;
    @MockBean private LabelaryPreviewService labelaryPreviewService;

    @Test
    void missingRequiredField_returns400WithFieldDetails() throws Exception {
        String body = """
            {
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123"
            }
            """;

        mockMvc.perform(post("/api/wristbands/preview/zpl")
                .header("X-API-Key", "changeme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.fields.eventName").exists());
    }

    @Test
    void missingApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/wristbands/preview/zpl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=GlobalExceptionHandlerTest
```

Expected: compilation errors because `WristbandController` does not exist yet. This is correct — proceed to the next tasks to implement the controller, then return to confirm these tests pass after Task 12.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/exception/ src/test/java/com/stup/wristbandprinter/exception/
git commit -m "feat: add custom exceptions and global exception handler"
```

---

## Task 5: Security — API Key Filter

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/security/ApiKeyAuthFilter.java`
- Create: `src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java`

- [ ] **Step 1: Create `ApiKeyAuthFilter.java`**

```java
package com.stup.wristbandprinter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String apiKey;

    public ApiKeyAuthFilter(@Value("${security.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader("X-API-Key");
        if (apiKey.equals(key)) {
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("api-client", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 2: Create `SecurityConfig.java`**

```java
package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@SecurityScheme(
    name = "ApiKeyAuth",
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.HEADER,
    paramName = "X-API-Key"
)
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(ApiKeyAuthFilter apiKeyAuthFilter) {
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Job management page and its SSE stream do not require API key
                // (browsers cannot set custom headers on EventSource)
                .requestMatchers(
                    "/jobs.html",
                    "/api/wristbands/jobs/stream",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 3: Verify the app starts and rejects unauthenticated requests**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local &
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/wristbands/preview/zpl
# Expected: 401
curl -s -o /dev/null -w "%{http_code}" -H "X-API-Key: local-dev-key" http://localhost:8080/api/wristbands/preview/zpl
# Expected: 404 (endpoint not yet implemented, but authenticated)
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/security/ src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java
git commit -m "feat: add API key authentication filter and security config"
```

---

## Task 6: LogoConversionService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/LogoConversionServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/LogoConversionServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.*;

class LogoConversionServiceTest {

    private WristbandProperties defaultProps() {
        WristbandProperties props = new WristbandProperties();
        props.setWidthDots(203);
        props.setLogoSideMarginDots(10);
        return props;
    }

    @Test
    void loadAndConvertLogo_producesValidGfCommand() throws Exception {
        // Create a small test PNG on disk
        File tmpPng = File.createTempFile("test-logo", ".png");
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 100, 50);
        g.dispose();
        ImageIO.write(img, "png", tmpPng);

        WristbandProperties props = defaultProps();
        props.setLogoPath(tmpPng.getAbsolutePath());

        LogoConversionService service = new LogoConversionService(props);
        service.loadAndConvertLogo();

        String gf = service.getGfCommand();
        assertThat(gf).startsWith("^GFA,");
        assertThat(service.getLogoHeightDots()).isGreaterThan(0);

        tmpPng.delete();
    }

    @Test
    void loadAndConvertLogo_throwsLogoNotFoundException_whenFileDoesNotExist() {
        WristbandProperties props = defaultProps();
        props.setLogoPath("/nonexistent/path/logo.png");

        LogoConversionService service = new LogoConversionService(props);

        assertThatThrownBy(service::loadAndConvertLogo)
            .isInstanceOf(LogoNotFoundException.class)
            .hasMessageContaining("Logo not found");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=LogoConversionServiceTest
```

Expected: `LogoConversionService` does not exist → compilation error.

- [ ] **Step 3: Create `LogoConversionService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

@Service
public class LogoConversionService {

    private static final Logger log = LoggerFactory.getLogger(LogoConversionService.class);

    private final WristbandProperties props;
    private String cachedGfCommand;
    private int logoHeightDots;

    public LogoConversionService(WristbandProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void loadAndConvertLogo() {
        String logoPath = props.getLogoPath();
        log.info("Loading logo from: {}", logoPath);
        try {
            Resource resource = resolveResource(logoPath);
            if (!resource.exists()) {
                throw new LogoNotFoundException("Logo not found at: " + logoPath);
            }
            BufferedImage original = ImageIO.read(resource.getInputStream());
            if (original == null) {
                throw new LogoNotFoundException("Could not decode image at: " + logoPath);
            }

            int targetWidth = props.getWidthDots() - 2 * props.getLogoSideMarginDots();
            int targetHeight = (int) ((double) original.getHeight() / original.getWidth() * targetWidth);
            this.logoHeightDots = targetHeight;

            BufferedImage scaled = scaleImage(original, targetWidth, targetHeight);
            // Pre-rotate 180° — both logos are printed upside down on the wristband
            BufferedImage rotated = rotate180(scaled);
            this.cachedGfCommand = encodeAsGf(rotated);

            log.info("Logo converted successfully. Dimensions: {}x{} dots", targetWidth, targetHeight);
        } catch (IOException e) {
            throw new LogoNotFoundException("Failed to load logo: " + e.getMessage(), e);
        }
    }

    public String getGfCommand() {
        return cachedGfCommand;
    }

    public int getLogoHeightDots() {
        return logoHeightDots;
    }

    private BufferedImage scaleImage(BufferedImage src, int w, int h) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate180(BufferedImage img) {
        BufferedImage result = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
        Graphics2D g = result.createGraphics();
        g.rotate(Math.PI, img.getWidth() / 2.0, img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return result;
    }

    private String encodeAsGf(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int bytesPerRow = (width + 7) / 8;
        StringBuilder hex = new StringBuilder();

        for (int y = 0; y < height; y++) {
            for (int bx = 0; bx < bytesPerRow; bx++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    if (x < width) {
                        int rgb = img.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int gv = (rgb >> 8) & 0xFF;
                        int bv = rgb & 0xFF;
                        int luminance = (r + gv + bv) / 3;
                        if (luminance < 128) {  // dark pixel → print
                            b |= (1 << (7 - bit));
                        }
                    }
                }
                hex.append(String.format("%02X", b));
            }
        }

        int totalBytes = bytesPerRow * height;
        return String.format("^GFA,%d,%d,%d,%s", hex.length(), totalBytes, bytesPerRow, hex);
    }

    private Resource resolveResource(String path) {
        if (path.startsWith("classpath:")) {
            return new ClassPathResource(path.substring("classpath:".length()));
        }
        return new FileSystemResource(path);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=LogoConversionServiceTest
```

Expected: Both tests pass. Note: the classpath test relies on `stup-logo.png` being present in `src/main/resources/images/`. Place a placeholder PNG there for now; replace with the real logo later.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/LogoConversionService.java src/test/java/com/stup/wristbandprinter/service/LogoConversionServiceTest.java
git commit -m "feat: add logo conversion service (PNG to ZPL ^GF, pre-rotated 180°)"
```

---

## Task 7: WristbandLayoutService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WristbandLayoutServiceTest {

    private WristbandLayoutService service;

    @BeforeEach
    void setUp() {
        service = new WristbandLayoutService();
    }

    @Test
    void buildData_mapsAllFieldsFromRequest() {
        WristbandPrintRequest request = new WristbandPrintRequest();
        request.setEventName("Pukkelpop 2026");
        request.setFirstName("Jan");
        request.setLastName("Janssens");
        request.setAssociationName("STUP vzw");
        request.setBarcodeValue("123456789");

        WristbandData data = service.buildData(request);

        assertThat(data.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(data.firstName()).isEqualTo("Jan");
        assertThat(data.lastName()).isEqualTo("Janssens");
        assertThat(data.associationName()).isEqualTo("STUP vzw");
        assertThat(data.barcodeValue()).isEqualTo("123456789");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=WristbandLayoutServiceTest
```

Expected: `WristbandLayoutService` does not exist → compilation error.

- [ ] **Step 3: Create `WristbandLayoutService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.stereotype.Service;

@Service
public class WristbandLayoutService {

    public WristbandData buildData(WristbandPrintRequest request) {
        return new WristbandData(
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            request.getAssociationName(),
            request.getBarcodeValue()
        );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=WristbandLayoutServiceTest
```

Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/WristbandLayoutService.java src/test/java/com/stup/wristbandprinter/service/WristbandLayoutServiceTest.java
git commit -m "feat: add WristbandLayoutService (request to domain mapping)"
```

---

## Task 8: ZplGeneratorService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/ZplGeneratorService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZplGeneratorServiceTest {

    @Mock
    private LogoConversionService logoConversionService;

    private ZplGeneratorService service;

    @BeforeEach
    void setUp() {
        when(logoConversionService.getGfCommand()).thenReturn("^GFA,8,8,1,FF000000");
        when(logoConversionService.getLogoHeightDots()).thenReturn(50);

        WristbandProperties props = new WristbandProperties();
        service = new ZplGeneratorService(props, logoConversionService);
    }

    @Test
    void generate_producesZplWithStartAndEndCommands() {
        WristbandData data = sampleData();
        String zpl = service.generate(data);
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsTextRotationCommand() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^A0B");
    }

    @Test
    void generate_containsBarcodeRotationCommand() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^BCB");
    }

    @Test
    void generate_containsLogoGraphicField() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^GFA");
    }

    @Test
    void generate_containsAllTextFields() {
        WristbandData data = sampleData();
        String zpl = service.generate(data);
        assertThat(zpl).contains("Pukkelpop 2026");
        assertThat(zpl).contains("Jan");
        assertThat(zpl).contains("Janssens");
        assertThat(zpl).contains("STUP vzw");
        assertThat(zpl).contains("123456789");
    }

    @Test
    void generate_stripsCaret_fromUserInput() {
        WristbandData data = new WristbandData(
            "Event^Name", "Ja^n", "Jan^ssens", "STUP^vzw", "^123"
        );
        String zpl = service.generate(data);
        assertThat(zpl).doesNotContain("Event^Name");
        assertThat(zpl).contains("EventName");
    }

    @Test
    void generate_containsLogoDuplicatedTwice() {
        String zpl = service.generate(sampleData());
        // logo GF command appears twice (top and bottom logo)
        int firstOccurrence = zpl.indexOf("^GFA,8,8");
        int secondOccurrence = zpl.indexOf("^GFA,8,8", firstOccurrence + 1);
        assertThat(secondOccurrence).isGreaterThan(firstOccurrence);
    }

    private WristbandData sampleData() {
        return new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123456789");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=ZplGeneratorServiceTest
```

Expected: `ZplGeneratorService` does not exist → compilation error.

- [ ] **Step 3: Create `ZplGeneratorService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import org.springframework.stereotype.Service;

@Service
public class ZplGeneratorService {

    // Gap between text lines in dots (along band length, with ^A0B rotation)
    private static final int INTER_LINE_GAP = 8;

    // Approximate dots added by the Human Readable Interpretation text below a barcode
    private static final int HRI_HEIGHT_DOTS = 24;

    private final WristbandProperties props;
    private final LogoConversionService logoConversionService;

    public ZplGeneratorService(WristbandProperties props, LogoConversionService logoConversionService) {
        this.props = props;
        this.logoConversionService = logoConversionService;
    }

    public String generate(WristbandData data) {
        WristbandProperties.Margins m = props.getMargins();
        WristbandProperties.Text t = props.getText();
        WristbandProperties.Barcode b = props.getBarcode();

        int logoH = logoConversionService.getLogoHeightDots();
        int sideMargin = props.getLogoSideMarginDots();

        // Calculate y positions (y increases toward adhesive end)
        int topLogoY        = m.getTopDots();
        int textBlockY      = topLogoY + logoH + m.getBetweenLogoAndText();
        int barcodeY        = textBlockY + textBlockHeight(t) + m.getBetweenTextAndBarcode();
        int hriExtra        = b.isShowHumanReadable() ? HRI_HEIGHT_DOTS : 0;
        int bottomLogoY     = barcodeY + b.getHeightDots() + hriExtra + m.getBetweenBarcodeAndLogo();

        StringBuilder zpl = new StringBuilder();

        // Label setup
        zpl.append("^XA");
        zpl.append(String.format("^PW%d", props.getWidthDots()));
        zpl.append(String.format("^LL%d", props.getLengthDots()));
        zpl.append("^CI28"); // UTF-8 encoding

        // Top logo — pre-rotated 180° in image data
        appendLogo(zpl, sideMargin, topLogoY);

        // Text block — ^A0B = 90° counter-clockwise rotation
        appendTextBlock(zpl, data, textBlockY, t);

        // Barcode — ^BCB = 90° counter-clockwise rotation
        appendBarcode(zpl, data.barcodeValue(), barcodeY, b);

        // Bottom logo — same pre-rotated image data
        appendLogo(zpl, sideMargin, bottomLogoY);

        zpl.append("^XZ");
        return zpl.toString();
    }

    private void appendLogo(StringBuilder zpl, int x, int y) {
        zpl.append("^FX Logo ^");
        zpl.append(String.format("^FO%d,%d", x, y));
        zpl.append(logoConversionService.getGfCommand());
    }

    private void appendTextBlock(StringBuilder zpl, WristbandData data,
                                  int startY, WristbandProperties.Text t) {
        zpl.append("^FX Text block ^");

        // Event name — smaller font, centered in band width
        int eventX = centerX(t.getFontSizeEvent());
        int eventY = startY;
        zpl.append(String.format("^FO%d,%d", eventX, eventY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeEvent(), t.getFontSizeEvent()));
        zpl.append(String.format("^FD%s^FS", sanitize(data.eventName())));

        // Full name — larger font, centered in band width
        int nameX = centerX(t.getFontSizeName());
        int nameY = eventY + t.getFontSizeEvent() + INTER_LINE_GAP;
        zpl.append(String.format("^FO%d,%d", nameX, nameY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeName(), t.getFontSizeName()));
        zpl.append(String.format("^FD%s %s^FS", sanitize(data.firstName()), sanitize(data.lastName())));

        // Association — smaller font, centered in band width
        int assocX = centerX(t.getFontSizeAssociation());
        int assocY = nameY + t.getFontSizeName() + INTER_LINE_GAP;
        zpl.append(String.format("^FO%d,%d", assocX, assocY));
        zpl.append(String.format("^A0B,%d,%d", t.getFontSizeAssociation(), t.getFontSizeAssociation()));
        zpl.append(String.format("^FD%s^FS", sanitize(data.associationName())));
    }

    private void appendBarcode(StringBuilder zpl, String value, int y,
                                WristbandProperties.Barcode b) {
        zpl.append("^FX Barcode ^");
        // Center the barcode in band width using its height as the x-extent
        int x = centerX(b.getHeightDots());
        String hri = b.isShowHumanReadable() ? "Y" : "N";
        zpl.append(String.format("^FO%d,%d", x, y));
        // ^BCB,height,hri,line,lineAbove — B = bottom-up (90° CCW)
        zpl.append(String.format("^BCB,%d,%s,N,N", b.getHeightDots(), hri));
        zpl.append(String.format("^FD%s^FS", sanitize(value)));
    }

    /**
     * Centers a field of the given height across the label width.
     * With ^A0B rotation, font height maps to the x-direction (across the band width).
     */
    private int centerX(int fieldHeight) {
        return (props.getWidthDots() - fieldHeight) / 2;
    }

    private int textBlockHeight(WristbandProperties.Text t) {
        return t.getFontSizeEvent() + INTER_LINE_GAP
             + t.getFontSizeName() + INTER_LINE_GAP
             + t.getFontSizeAssociation();
    }

    /** Removes ZPL control characters from user-supplied text. */
    private String sanitize(String text) {
        return text.replaceAll("[\\^~]", "");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=ZplGeneratorServiceTest
```

Expected: All 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/ZplGeneratorService.java src/test/java/com/stup/wristbandprinter/service/ZplGeneratorServiceTest.java
git commit -m "feat: add ZPL generator service with layout, text centering, and barcode"
```

---

## Task 9: PrinterService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/PrinterService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/PrinterServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/PrinterServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrinterProperties;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterServiceTest {

    @Test
    void send_writesZplToSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(client.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            PrinterService service = new PrinterService(propsFor("localhost", port));
            service.send("^XA^XZ");

            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("^XA^XZ");
        }
    }

    @Test
    void send_throwsPrinterUnavailableException_whenHostUnreachable() {
        PrinterService service = new PrinterService(propsFor("localhost", 19999));

        assertThatThrownBy(() -> service.send("^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class)
            .hasMessageContaining("localhost");
    }

    private PrinterProperties propsFor(String host, int port) {
        PrinterProperties props = new PrinterProperties();
        props.setHost(host);
        props.setPort(port);
        props.setTimeoutMs(1000);
        return props;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=PrinterServiceTest
```

Expected: compilation error — `PrinterService` does not exist.

- [ ] **Step 3: Create `PrinterService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrinterProperties;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Service
public class PrinterService {

    private static final Logger log = LoggerFactory.getLogger(PrinterService.class);

    private final PrinterProperties props;

    public PrinterService(PrinterProperties props) {
        this.props = props;
    }

    public void send(String zpl) {
        log.info("Sending ZPL to {}:{}", props.getHost(), props.getPort());
        try (Socket socket = new Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(props.getHost(), props.getPort()),
                props.getTimeoutMs()
            );
            socket.setSoTimeout(props.getTimeoutMs());
            OutputStream out = socket.getOutputStream();
            out.write(zpl.getBytes(StandardCharsets.UTF_8));
            out.flush();
            log.info("ZPL sent successfully ({} bytes)", zpl.length());
        } catch (IOException e) {
            throw new PrinterUnavailableException(
                "Could not connect to printer at " + props.getHost() + ":" + props.getPort()
                    + " — " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=PrinterServiceTest
```

Expected: Both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrinterService.java src/test/java/com/stup/wristbandprinter/service/PrinterServiceTest.java
git commit -m "feat: add PrinterService (raw TCP socket to Zebra printer)"
```

---

## Task 10: LabelaryPreviewService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/LabelaryPreviewServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/LabelaryPreviewServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.LabelaryProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LabelaryPreviewServiceTest {

    @Test
    void renderPreview_returnsImageBytes() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/printers/8dpmm/labels/1x11/0/")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));

        LabelaryPreviewService service = buildService(template, "http://fake.labelary.com");
        byte[] result = service.renderPreview("^XA^XZ");

        assertThat(result).containsExactly(1, 2, 3);
        server.verify();
    }

    @Test
    void renderPreview_throwsLabelaryUnavailableException_onHttpError() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/printers")))
            .andRespond(withServerError());

        LabelaryPreviewService service = buildService(template, "http://fake.labelary.com");

        assertThatThrownBy(() -> service.renderPreview("^XA^XZ"))
            .isInstanceOf(LabelaryUnavailableException.class);
    }

    private LabelaryPreviewService buildService(RestTemplate template, String baseUrl) {
        LabelaryProperties labelaryProps = new LabelaryProperties();
        labelaryProps.setBaseUrl(baseUrl);
        labelaryProps.setTimeoutMs(3000);

        WristbandProperties wristbandProps = new WristbandProperties();
        wristbandProps.setDpi(203);

        return new LabelaryPreviewService(template, labelaryProps, wristbandProps);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=LabelaryPreviewServiceTest
```

Expected: compilation error — `LabelaryPreviewService` does not exist.

- [ ] **Step 3: Create `LabelaryPreviewService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.LabelaryProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class LabelaryPreviewService {

    private static final Logger log = LoggerFactory.getLogger(LabelaryPreviewService.class);

    private final RestTemplate restTemplate;
    private final LabelaryProperties labelaryProps;
    private final int dpmm;  // dots per mm derived from configured DPI

    public LabelaryPreviewService(LabelaryProperties labelaryProps, WristbandProperties wristbandProps) {
        this.restTemplate = new RestTemplate();
        this.labelaryProps = labelaryProps;
        this.dpmm = Math.round(wristbandProps.getDpi() / 25.4f);
    }

    // Package-private constructor for tests — accepts a pre-configured RestTemplate
    LabelaryPreviewService(RestTemplate restTemplate, LabelaryProperties labelaryProps,
                           WristbandProperties wristbandProps) {
        this.restTemplate = restTemplate;
        this.labelaryProps = labelaryProps;
        this.dpmm = Math.round(wristbandProps.getDpi() / 25.4f);
    }

    public byte[] renderPreview(String zpl) {
        String url = labelaryProps.getBaseUrl()
            + "/v1/printers/{dpmm}dpmm/labels/{width}x{height}/0/";

        log.info("Requesting Labelary preview at {}dpmm", dpmm);
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>(zpl, headers);

            byte[] result = restTemplate.postForObject(url, entity, byte[].class,
                dpmm, "1", "11");

            if (result == null) {
                throw new LabelaryUnavailableException("Labelary returned empty response");
            }
            return result;
        } catch (RestClientException e) {
            throw new LabelaryUnavailableException("Labelary API unavailable: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=LabelaryPreviewServiceTest
```

Expected: Both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/LabelaryPreviewService.java src/test/java/com/stup/wristbandprinter/service/LabelaryPreviewServiceTest.java
git commit -m "feat: add LabelaryPreviewService (ZPL to PNG via Labelary HTTP API)"
```

---

## Task 11: PrintQueueService

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java`
- Create: `src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrintQueueServiceTest {

    @Mock private WristbandLayoutService layoutService;
    @Mock private ZplGeneratorService zplGeneratorService;
    @Mock private PrinterService printerService;

    private PrintQueueService service;

    @BeforeEach
    void setUp() {
        service = new PrintQueueService(layoutService, zplGeneratorService, printerService);
        service.startWorker();
    }

    @AfterEach
    void tearDown() {
        service.stopWorker();
    }

    @Test
    void enqueue_returnsJobWithPendingStatus() {
        PrintJob job = service.enqueue(sampleRequest());
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(job.getJobId()).isNotNull();
    }

    @Test
    void enqueue_jobBecomesAfterProcessing() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(printerService).send(any());

        PrintJob job = service.enqueue(sampleRequest());
        boolean processed = latch.await(3, TimeUnit.SECONDS);

        assertThat(processed).isTrue();
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
    }

    @Test
    void enqueue_jobBecomesFailed_whenPrinterThrows() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> {
            latch.countDown();
            throw new PrinterUnavailableException("Printer down");
        }).when(printerService).send(any());

        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100); // allow status update to propagate

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(job.getError()).contains("Printer down");
    }

    @Test
    void getJobs_returnsAllJobs() {
        service.enqueue(sampleRequest());
        service.enqueue(sampleRequest());
        assertThat(service.getJobs(null)).hasSize(2);
    }

    @Test
    void getJobs_filtersByStatus() {
        service.enqueue(sampleRequest());
        List<?> pending = service.getJobs(PrintJobStatus.PENDING);
        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(j -> ((PrintJob) j).getStatus() == PrintJobStatus.PENDING);
    }

    @Test
    void clearCompleted_removesOnlyDoneAndFailedJobs() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(printerService).send(any());

        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(service.getJobs(null)).hasSize(1);
        service.clearCompleted();
        assertThat(service.getJobs(null)).isEmpty();
    }

    private WristbandPrintRequest sampleRequest() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }

    private WristbandData sampleData() {
        return new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123456789");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=PrintQueueServiceTest
```

Expected: compilation error — `PrintQueueService` does not exist.

- [ ] **Step 3: Create `PrintQueueService.java`**

```java
package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class PrintQueueService {

    private static final Logger log = LoggerFactory.getLogger(PrintQueueService.class);

    private final LinkedBlockingQueue<PrintJob> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<UUID, PrintJob> jobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final WristbandLayoutService layoutService;
    private final ZplGeneratorService zplGeneratorService;
    private final PrinterService printerService;

    private ExecutorService worker;

    public PrintQueueService(WristbandLayoutService layoutService,
                              ZplGeneratorService zplGeneratorService,
                              PrinterService printerService) {
        this.layoutService = layoutService;
        this.zplGeneratorService = zplGeneratorService;
        this.printerService = printerService;
    }

    @PostConstruct
    public void startWorker() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "print-queue-worker");
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::processQueue);
        log.info("Print queue worker started");
    }

    @PreDestroy
    public void stopWorker() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Print queue worker stopped");
    }

    public PrintJob enqueue(WristbandPrintRequest request) {
        PrintJob job = new PrintJob(UUID.randomUUID(), request);
        jobs.put(job.getJobId(), job);
        queue.add(job);
        broadcastUpdate(job);
        log.info("Job {} enqueued for event: {}, barcode: {}",
            job.getJobId(), request.getEventName(), request.getBarcodeValue());
        return job;
    }

    public Optional<PrintJob> getJob(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<PrintJob> getJobs(PrintJobStatus statusFilter) {
        return jobs.values().stream()
            .filter(job -> statusFilter == null || job.getStatus() == statusFilter)
            .sorted(Comparator.comparing(PrintJob::getSubmittedAt))
            .collect(Collectors.toList());
    }

    public void clearCompleted() {
        jobs.values().removeIf(job ->
            job.getStatus() == PrintJobStatus.DONE || job.getStatus() == PrintJobStatus.FAILED);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrintJob job = queue.take();
                updateJobStatus(job, PrintJobStatus.PRINTING, null);
                try {
                    WristbandData data = layoutService.buildData(job.getRequest());
                    String zpl = zplGeneratorService.generate(data);
                    printerService.send(zpl);
                    updateJobStatus(job, PrintJobStatus.DONE, null);
                } catch (PrinterUnavailableException e) {
                    log.warn("Print job {} failed: {}", job.getJobId(), e.getMessage());
                    updateJobStatus(job, PrintJobStatus.FAILED, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void updateJobStatus(PrintJob job, PrintJobStatus status, String error) {
        job.setStatus(status);
        job.setError(error);
        if (status == PrintJobStatus.DONE || status == PrintJobStatus.FAILED) {
            job.setCompletedAt(java.time.Instant.now());
        }
        broadcastUpdate(job);
    }

    private void broadcastUpdate(PrintJob job) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(job.toResponse()));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=PrintQueueServiceTest
```

Expected: All 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/service/PrintQueueService.java src/test/java/com/stup/wristbandprinter/service/PrintQueueServiceTest.java
git commit -m "feat: add async print queue with SSE broadcast (LinkedBlockingQueue + worker thread)"
```

---

## Task 12: WristbandController

**Files:**
- Create: `src/main/java/com/stup/wristbandprinter/controller/WristbandController.java`
- Create: `src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/stup/wristbandprinter/controller/WristbandControllerTest.java
package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import com.stup.wristbandprinter.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandController.class)
class WristbandControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PrintQueueService printQueueService;
    @MockBean WristbandLayoutService wristbandLayoutService;
    @MockBean ZplGeneratorService zplGeneratorService;
    @MockBean LabelaryPreviewService labelaryPreviewService;

    private static final String API_KEY = "changeme";

    @Test
    void print_returns202WithJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        PrintJob job = new PrintJob(jobId, sampleRequest());
        when(printQueueService.enqueue(any())).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void print_returns400_whenFieldMissing() throws Exception {
        String body = """
            {"firstName":"Jan","lastName":"Janssens","associationName":"STUP vzw","barcodeValue":"123"}
            """;
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.eventName").exists());
    }

    @Test
    void print_returns401_whenApiKeyMissing() throws Exception {
        mockMvc.perform(post("/api/wristbands/print")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void previewZpl_returnsZplString() throws Exception {
        WristbandData data = new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);
        when(zplGeneratorService.generate(data)).thenReturn("^XA^XZ");

        mockMvc.perform(post("/api/wristbands/preview/zpl")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string("^XA^XZ"));
    }

    @Test
    void previewImage_returnsPngBytes() throws Exception {
        WristbandData data = new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);
        when(zplGeneratorService.generate(data)).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview("^XA^XZ")).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/wristbands/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void previewImage_returns503_whenLabelaryUnavailable() throws Exception {
        WristbandData data = new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123");
        when(wristbandLayoutService.buildData(any())).thenReturn(data);
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any()))
            .thenThrow(new LabelaryUnavailableException("Labelary down"));

        mockMvc.perform(post("/api/wristbands/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("Labelary unavailable"));
    }

    @Test
    void getJobById_returns200_whenJobExists() throws Exception {
        UUID jobId = UUID.randomUUID();
        PrintJob job = new PrintJob(jobId, sampleRequest());
        when(printQueueService.getJob(jobId)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/wristbands/jobs/" + jobId)
                .header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void getJobById_returns404_whenJobNotFound() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(printQueueService.getJob(jobId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/wristbands/jobs/" + jobId)
                .header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void getJobs_returnsList() throws Exception {
        when(printQueueService.getJobs(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/wristbands/jobs")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void reprint_returns202_whenJobExists() throws Exception {
        UUID originalId = UUID.randomUUID();
        PrintJob original = new PrintJob(originalId, sampleRequest());
        UUID newId = UUID.randomUUID();
        PrintJob newJob = new PrintJob(newId, sampleRequest());

        when(printQueueService.getJob(originalId)).thenReturn(Optional.of(original));
        when(printQueueService.enqueue(any())).thenReturn(newJob);

        mockMvc.perform(post("/api/wristbands/jobs/" + originalId + "/reprint")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(newId.toString()));
    }

    @Test
    void clearCompleted_returns204() throws Exception {
        mockMvc.perform(delete("/api/wristbands/jobs/completed")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isNoContent());

        verify(printQueueService).clearCompleted();
    }

    private WristbandPrintRequest sampleRequest() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=WristbandControllerTest
```

Expected: compilation error — `WristbandController` does not exist.

- [ ] **Step 3: Create `WristbandController.java`**

```java
package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    @PostMapping(value = "/preview/zpl", produces = MediaType.TEXT_PLAIN_VALUE)
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
    @Operation(summary = "Get status of a specific print job")
    public ResponseEntity<PrintJobResponse> getJob(@PathVariable UUID jobId) {
        return printQueueService.getJob(jobId)
            .map(job -> ResponseEntity.ok(job.toResponse()))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to real-time job status updates via SSE (no API key required)")
    @SecurityRequirement(name = "")  // excluded from API key enforcement
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

    @DeleteMapping("/jobs/completed")
    @Operation(summary = "Remove all DONE and FAILED jobs from the queue")
    public ResponseEntity<Void> clearCompleted() {
        printQueueService.clearCompleted();
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
mvn test
```

Expected: All tests pass, including `GlobalExceptionHandlerTest` which was deferred in Task 4.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/controller/ src/test/java/com/stup/wristbandprinter/controller/
git commit -m "feat: add WristbandController with all endpoints"
```

---

## Task 13: Job Management HTML Page

**Files:**
- Create: `src/main/resources/static/jobs.html`

- [ ] **Step 1: Create `jobs.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>STUP — Print Queue</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        .badge-PENDING    { background-color: #6c757d; }
        .badge-PRINTING   { background-color: #0d6efd; }
        .badge-DONE       { background-color: #198754; }
        .badge-FAILED     { background-color: #dc3545; }
        #sse-status { font-size: 0.8rem; }
    </style>
</head>
<body class="bg-light">
<div class="container py-4">
    <div class="d-flex align-items-center justify-content-between mb-3">
        <h1 class="h3 mb-0">STUP Print Queue</h1>
        <span id="sse-status" class="text-muted">Connecting…</span>
    </div>

    <!-- API key input -->
    <div class="card mb-3" id="api-key-card">
        <div class="card-body d-flex gap-2 align-items-center">
            <label class="form-label mb-0 fw-semibold" for="api-key-input">API Key:</label>
            <input type="password" id="api-key-input" class="form-control form-control-sm w-auto"
                   placeholder="Enter API key" style="min-width:220px">
            <button class="btn btn-sm btn-primary" onclick="saveApiKey()">Save</button>
            <span id="api-key-status" class="text-muted small"></span>
        </div>
    </div>

    <!-- Controls -->
    <div class="d-flex gap-2 mb-3">
        <select id="status-filter" class="form-select form-select-sm w-auto" onchange="applyFilter()">
            <option value="">All statuses</option>
            <option value="PENDING">PENDING</option>
            <option value="PRINTING">PRINTING</option>
            <option value="DONE">DONE</option>
            <option value="FAILED">FAILED</option>
        </select>
        <button class="btn btn-sm btn-outline-danger" onclick="clearCompleted()">Clear completed</button>
    </div>

    <!-- Job table -->
    <div class="card">
        <div class="card-body p-0">
            <table class="table table-hover mb-0" id="jobs-table">
                <thead class="table-light">
                    <tr>
                        <th>Job ID</th>
                        <th>Event</th>
                        <th>Status</th>
                        <th>Submitted</th>
                        <th>Completed</th>
                        <th>Error</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="jobs-body">
                    <tr id="empty-row"><td colspan="7" class="text-center text-muted py-3">No jobs yet.</td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>

<script>
    // Jobs stored by jobId for upsert
    const jobs = {};
    let eventSource = null;

    // --- API key ---
    function getApiKey() {
        return sessionStorage.getItem('apiKey') || '';
    }

    function saveApiKey() {
        const key = document.getElementById('api-key-input').value.trim();
        sessionStorage.setItem('apiKey', key);
        document.getElementById('api-key-status').textContent = key ? '✓ Saved' : 'Cleared';
    }

    window.addEventListener('load', () => {
        const stored = getApiKey();
        if (stored) {
            document.getElementById('api-key-input').value = stored;
            document.getElementById('api-key-status').textContent = '✓ Loaded from session';
        }
        connectSse();
    });

    // --- SSE ---
    function connectSse() {
        if (eventSource) eventSource.close();
        eventSource = new EventSource('/api/wristbands/jobs/stream');

        eventSource.onopen = () => {
            document.getElementById('sse-status').textContent = '● Live';
            document.getElementById('sse-status').className = 'text-success small';
        };

        eventSource.onmessage = (event) => {
            const job = JSON.parse(event.data);
            jobs[job.jobId] = job;
            renderTable();
        };

        eventSource.onerror = () => {
            document.getElementById('sse-status').textContent = '○ Reconnecting…';
            document.getElementById('sse-status').className = 'text-warning small';
        };
    }

    // --- Rendering ---
    function applyFilter() {
        renderTable();
    }

    function renderTable() {
        const filter = document.getElementById('status-filter').value;
        const tbody = document.getElementById('jobs-body');
        tbody.innerHTML = '';

        const filtered = Object.values(jobs)
            .filter(j => !filter || j.status === filter)
            .sort((a, b) => a.submittedAt.localeCompare(b.submittedAt));

        if (filtered.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-3">No jobs.</td></tr>';
            return;
        }

        filtered.forEach(job => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><small class="font-monospace">${job.jobId.substring(0, 8)}…</small></td>
                <td>${escHtml(job.eventName)}</td>
                <td><span class="badge badge-${job.status}">${job.status}</span></td>
                <td><small>${formatTs(job.submittedAt)}</small></td>
                <td><small>${job.completedAt ? formatTs(job.completedAt) : '—'}</small></td>
                <td><small class="text-danger">${job.error ? escHtml(job.error) : ''}</small></td>
                <td>
                    ${(job.status === 'DONE' || job.status === 'FAILED')
                        ? `<button class="btn btn-sm btn-outline-primary" onclick="reprint('${job.jobId}')">Reprint</button>`
                        : ''}
                </td>
            `;
            tbody.appendChild(tr);
        });
    }

    function formatTs(iso) {
        return new Date(iso).toLocaleTimeString();
    }

    function escHtml(str) {
        return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    // --- Actions ---
    async function reprint(jobId) {
        const key = getApiKey();
        if (!key) { alert('Enter an API key first.'); return; }
        const res = await fetch(`/api/wristbands/jobs/${jobId}/reprint`, {
            method: 'POST',
            headers: { 'X-API-Key': key }
        });
        if (!res.ok) {
            const body = await res.json();
            alert('Reprint failed: ' + (body.message || res.status));
        }
    }

    async function clearCompleted() {
        const key = getApiKey();
        if (!key) { alert('Enter an API key first.'); return; }
        const res = await fetch('/api/wristbands/jobs/completed', {
            method: 'DELETE',
            headers: { 'X-API-Key': key }
        });
        if (res.ok) {
            Object.keys(jobs).forEach(id => {
                if (jobs[id].status === 'DONE' || jobs[id].status === 'FAILED') delete jobs[id];
            });
            renderTable();
        } else {
            alert('Clear failed: ' + res.status);
        }
    }
</script>
</body>
</html>
```

- [ ] **Step 2: Start the application and verify the page loads**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Open `http://localhost:8080/jobs.html` — the page should load without a 404, show the empty job table, and display "Live" status once the SSE connection opens.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/jobs.html
git commit -m "feat: add job management page with SSE live updates, reprint and clear actions"
```

---

## Task 14: Docker, .env.example, and README

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `.dockerignore`
- Create: `README.md`

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/wristband-printer-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create `.dockerignore`**

```
target/
.git/
*.md
docs/
```

- [ ] **Step 3: Create `docker-compose.yml`**

```yaml
services:
  wristband-printer:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SECURITY_API_KEY=${API_KEY}
      - PRINTER_HOST=${PRINTER_HOST}
    volumes:
      # Mount the directory containing stup-logo.png
      - /opt/stup/images:/opt/stup/images:ro
    restart: unless-stopped
```

- [ ] **Step 4: Create `.env.example`**

```dotenv
# Copy this file to .env and fill in the values
API_KEY=your-strong-api-key-here
PRINTER_HOST=192.168.1.100
```

- [ ] **Step 5: Verify Docker build succeeds**

```bash
docker build -t stup/wristband-printer .
```

Expected: Build completes and produces a runnable image. The multi-stage build copies the JAR from the Maven stage into the JRE runtime stage.

- [ ] **Step 6: Create `README.md`**

```markdown
# STUP Wristband Printer Service

Java 21 / Spring Boot API that generates ZPL wristband labels for Zebra printers.
Used by the STUP Symfony event application to print staff wristbands at events.

---

## Running locally

**Prerequisites:** Java 21, Maven 3.9+

1. Place `stup-logo.png` in `src/main/resources/images/`
2. Edit `src/main/resources/application-local.yml` — set `printer.host` to your printer's IP
3. Start:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Application starts on **http://localhost:8080**

---

## Docker build and run

```bash
# Build
docker build -t stup/wristband-printer .

# Run (single container)
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SECURITY_API_KEY=your-key \
  -e PRINTER_HOST=192.168.1.100 \
  -v /opt/stup/images:/opt/stup/images:ro \
  stup/wristband-printer

# Run with docker-compose
cp .env.example .env   # fill in API_KEY and PRINTER_HOST
docker compose up -d
```

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `printer.host` | `localhost` | Zebra printer IP address |
| `printer.port` | `9100` | Zebra printer TCP port |
| `printer.timeout-ms` | `5000` | Connection timeout in milliseconds |
| `wristband.dpi` | `203` | Printer DPI (203 or 300) |
| `wristband.logo-path` | `classpath:images/stup-logo.png` | Absolute path or `classpath:` path to STUP logo PNG |
| `wristband.logo-side-margin-dots` | `10` | Left/right margin around logo in dots |
| `wristband.margins.*` | see YAML | Spacing between layout elements in dots |
| `wristband.text.*` | see YAML | Font sizes for event name, staff name, association |
| `wristband.barcode.type` | `CODE128` | Barcode symbology |
| `wristband.barcode.height-dots` | `100` | Barcode height in dots |
| `wristband.barcode.show-human-readable` | `true` | Show text below barcode |
| `labelary.base-url` | `http://api.labelary.com` | Labelary API base URL |
| `security.api-key` | `changeme` | Static API key — override in production |

**Profile activation:**
- Local: `--spring.profiles.active=local`
- Production: `SPRING_PROFILES_ACTIVE=prod` env var

**ZPL coordinate calibration:** All layout positions are configurable via `wristband.margins.*` and `wristband.text.*`. After first test print, adjust values in `application-prod.yml` without code changes.

---

## API endpoints

All endpoints (except `/api/wristbands/jobs/stream` and `/jobs.html`) require:
```
X-API-Key: <your-api-key>
```

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/wristbands/print` | Enqueue a print job → `202 + jobId` |
| `POST` | `/api/wristbands/preview/zpl` | Return generated ZPL as plain text |
| `POST` | `/api/wristbands/preview/image` | Return rendered PNG via Labelary |
| `GET` | `/api/wristbands/jobs` | List all jobs (`?status=PENDING|PRINTING|DONE|FAILED`) |
| `GET` | `/api/wristbands/jobs/{jobId}` | Get job status |
| `GET` | `/api/wristbands/jobs/stream` | SSE stream — real-time job updates |
| `POST` | `/api/wristbands/jobs/{jobId}/reprint` | Reprint a previous job |
| `DELETE` | `/api/wristbands/jobs/completed` | Remove DONE and FAILED jobs |

**Example print request:**
```bash
curl -X POST http://localhost:8080/api/wristbands/print \
  -H "X-API-Key: local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{
    "eventName": "Pukkelpop 2026",
    "firstName": "Jan",
    "lastName": "Janssens",
    "associationName": "STUP vzw",
    "barcodeValue": "123456789"
  }'
```

**Example ZPL preview:**
```bash
curl -X POST http://localhost:8080/api/wristbands/preview/zpl \
  -H "X-API-Key: local-dev-key" \
  -H "Content-Type: application/json" \
  -d '{"eventName":"Pukkelpop 2026","firstName":"Jan","lastName":"Janssens","associationName":"STUP vzw","barcodeValue":"123456789"}' \
  | pbcopy   # paste into https://labelary.com/viewer.html
```

---

## Labelary preview

The `/api/wristbands/preview/image` endpoint sends the generated ZPL to the
[Labelary API](https://labelary.com/service.html) and returns the rendered PNG.

To preview manually, use `/api/wristbands/preview/zpl` to get the ZPL string,
then paste it at [labelary.com/viewer.html](https://labelary.com/viewer.html).
Set width to **1**, height to **11**, density to **8dpmm** (203 dpi).

---

## Job management UI

Open **http://localhost:8080/jobs.html** in a browser.

- Enter the API key in the input at the top (stored in `sessionStorage` for the session)
- The job table updates in real-time via Server-Sent Events — no page refresh needed
- Use the **Reprint** button on any DONE or FAILED job to re-enqueue it
- Use **Clear completed** to remove DONE and FAILED jobs from the view

---

## Swagger UI

Interactive API docs available at:
- **http://localhost:8080/swagger-ui.html**
- OpenAPI spec: **http://localhost:8080/v3/api-docs**

Click **Authorize** in Swagger UI and enter your API key to test endpoints interactively.

---

## Running tests

```bash
mvn test
```

No external dependencies required — the printer and Labelary are mocked in tests.
```

- [ ] **Step 7: Run full test suite one final time**

```bash
mvn test
```

Expected: All tests pass. Note the expected count per class:
- `LogoConversionServiceTest`: 2
- `WristbandLayoutServiceTest`: 1
- `ZplGeneratorServiceTest`: 6
- `PrinterServiceTest`: 2
- `LabelaryPreviewServiceTest`: 2
- `PrintQueueServiceTest`: 6
- `WristbandControllerTest`: 10
- `GlobalExceptionHandlerTest`: 2

Total: **31 tests, 0 failures**

- [ ] **Step 8: Commit**

```bash
git add Dockerfile docker-compose.yml .env.example .dockerignore README.md
git commit -m "feat: add Dockerfile, docker-compose, and README"
```

---

## Post-Implementation: Logo and ZPL Calibration

> These steps happen after the build, not during it.

1. Place the actual `stup-logo.png` in `src/main/resources/images/` (or at `/opt/stup/images/stup-logo.png` on the production server)
2. Use `POST /api/wristbands/preview/zpl` to get the ZPL and paste into [labelary.com/viewer.html](https://labelary.com/viewer.html) (1x11, 8dpmm) to visually verify the layout
3. Use `POST /api/wristbands/preview/image` for a quick PNG preview without leaving the API
4. Adjust `wristband.margins.*` and `wristband.text.*` values in the active YAML until the layout looks correct
5. Do a test print on the physical printer and verify logo rotation, text centering, and barcode readability
