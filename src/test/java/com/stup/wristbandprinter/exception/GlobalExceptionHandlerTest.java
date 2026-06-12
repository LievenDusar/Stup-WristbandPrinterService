package com.stup.wristbandprinter.exception;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandGalleryCatalog;
import com.stup.wristbandprinter.service.WristbandZplResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties({AdminProperties.class, WristbandProperties.class})
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private PrintQueueService printQueueService;
    @MockitoBean private WristbandZplResolver wristbandZplResolver;
    @MockitoBean private LabelaryPreviewService labelaryPreviewService;
    @MockitoBean private com.stup.wristbandprinter.cluster.PrinterRegistry printerRegistry;
    @MockitoBean private PreviewColorService previewColorService;
    @MockitoBean private WristbandGalleryCatalog wristbandGalleryCatalog;

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

        mockMvc.perform(post("/api/wristbands/crew/preview/zpl")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.fields.eventName").exists());
    }

    @Test
    void queueFull_returns429() throws Exception {
        Mockito.when(printQueueService.enqueue(Mockito.any()))
            .thenThrow(new QueueFullException("Print queue is full"));

        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123"
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void unknownPrinter_returns400() throws Exception {
        Mockito.when(printQueueService.enqueue(Mockito.any()))
            .thenThrow(new UnknownPrinterException("Unknown printer id: nope"));

        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123",
              "printerId": "nope"
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Unknown printer"));
    }

    @Test
    void invalidCopies_returns400() throws Exception {
        Mockito.when(printQueueService.enqueue(Mockito.any()))
            .thenThrow(new InvalidCopiesException("copies must be between 1 and 200 (was 999)"));

        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123",
              "copies": 999
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid copies"));
    }

    @Test
    void wrongHttpMethod_returns405() throws Exception {
        // GET /crew/print: only POST is mapped → 405 Method Not Allowed
        mockMvc.perform(get("/api/wristbands/crew/print")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void missingApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/wristbands/crew/preview/zpl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
