package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties({AdminProperties.class, WristbandProperties.class})
@TestPropertySource(properties = {
    "security.api-key=test-key",
    "security.admin.password=pw",
    "wristband.stock-colors[2]=#800080"
})
class WristbandControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean PrintQueueService printQueueService;
    @MockitoBean WristbandZplResolver wristbandZplResolver;
    @MockitoBean LabelaryPreviewService labelaryPreviewService;
    @MockitoBean PrinterRegistry printerRegistry;
    @MockitoBean PreviewColorService previewColorService;
    @MockitoBean WristbandGalleryCatalog galleryCatalog;

    private static final String API_KEY = "test-key";

    @Test
    void print_returns202WithJobId() throws Exception {
        UUID jobId = UUID.randomUUID();
        PrintJob job = new PrintJob(jobId, sampleRequest());
        when(printQueueService.enqueue(any())).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/crew/print")
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
            {"firstName":"Jan","lastName":"Janssens","clubName":"STUP vzw","barcodeValue":"123"}
            """;
        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.eventName").exists());
    }

    @Test
    void print_returns401_whenApiKeyMissing() throws Exception {
        mockMvc.perform(post("/api/wristbands/crew/print")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void print_returns401_whenApiKeyWrong() throws Exception {
        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void crewPrint_oldUrl_redirectsTo308() throws Exception {
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is(308));
    }

    @Test
    void previewZpl_returnsZplString() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");

        mockMvc.perform(post("/api/wristbands/crew/preview/zpl")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string("^XA^XZ"));
    }

    @Test
    void previewImage_returnsPngBytes() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview("^XA^XZ")).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/wristbands/crew/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void previewImage_returns503_whenLabelaryUnavailable() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any()))
            .thenThrow(new LabelaryUnavailableException("Labelary down"));

        mockMvc.perform(post("/api/wristbands/crew/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("Labelary unavailable"));
    }

    @Test
    void previewImage_passesTemplateIdToResolver() throws Exception {
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any())).thenReturn(new byte[]{1});

        UUID templateId = UUID.randomUUID();
        WristbandPrintRequest req = sampleRequest();
        req.setTemplateId(templateId);

        mockMvc.perform(post("/api/wristbands/crew/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<WristbandPrintRequest> captor =
            org.mockito.ArgumentCaptor.forClass(WristbandPrintRequest.class);
        verify(wristbandZplResolver).resolve(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTemplateId()).isEqualTo(templateId);
    }

    @Test
    void crewPreviewImage_withStockColor_tintsPng() throws Exception {
        byte[] rawPng = new byte[]{1, 2, 3};
        byte[] tintedPng = new byte[]{4, 5, 6};
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        when(labelaryPreviewService.renderPreview(any())).thenReturn(rawPng);
        when(previewColorService.tint(rawPng, "#800080")).thenReturn(tintedPng);

        WristbandPrintRequest req = sampleRequest();
        req.setStockColorCode(2);

        mockMvc.perform(post("/api/wristbands/crew/preview/image")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));

        verify(previewColorService).tint(rawPng, "#800080");
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

    @Test
    void reprint_returns404_whenJobNotFound() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(printQueueService.getJob(jobId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/wristbands/jobs/" + jobId + "/reprint")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void reprint_withCopiesParam_overridesCopies() throws Exception {
        UUID jobId = UUID.randomUUID();
        WristbandPrintRequest original = new WristbandPrintRequest();
        original.setEventName("Pukkelpop 2026");
        original.setFirstName("Jan"); original.setLastName("Janssens");
        original.setClubName("STUP vzw"); original.setBarcodeValue("123");
        original.setCopies(1);
        PrintJob originalJob = new PrintJob(jobId, original, "printer-1", "Inkom");
        when(printQueueService.getJob(jobId)).thenReturn(java.util.Optional.of(originalJob));

        ArgumentCaptor<PrintableRequest> captor = ArgumentCaptor.forClass(PrintableRequest.class);
        when(printQueueService.enqueue(captor.capture()))
            .thenAnswer(inv -> new PrintJob(UUID.randomUUID(), inv.getArgument(0)));

        mockMvc.perform(post("/api/wristbands/jobs/" + jobId + "/reprint?copies=50")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isAccepted());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCopies()).isEqualTo(50);
    }

    @Test
    void crewPrint_returns400_whenCopiesBelowOne() throws Exception {
        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "clubName": "STUP vzw",
              "barcodeValue": "123",
              "copies": 0
            }
            """;

        mockMvc.perform(post("/api/wristbands/crew/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void streamJobs_requiresApiKey() throws Exception {
        mockMvc.perform(get("/api/wristbands/jobs/stream")
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void streamJobs_isAccessibleWithApiKey() throws Exception {
        when(printQueueService.subscribe()).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/wristbands/jobs/stream")
                .header("X-API-Key", API_KEY)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk());
    }

    @Test
    void getJob_returnsFullDetailFields() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        PrintJob job = new PrintJob(id, r);
        Mockito.when(printQueueService.getJob(id)).thenReturn(java.util.Optional.of(job));

        mockMvc.perform(get("/api/wristbands/jobs/" + id)
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Jan"))
            .andExpect(jsonPath("$.lastName").value("Janssens"))
            .andExpect(jsonPath("$.barcodeValue").value("123456789"));
    }

    @Test
    void cancel_pendingJob_returns200() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        PrintJob job = new PrintJob(id, r);
        Mockito.when(printQueueService.cancel(id)).thenReturn(job);

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk());
    }

    @Test
    void cancel_alreadyStarted_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.cancel(id))
            .thenThrow(new com.stup.wristbandprinter.exception.JobNotCancellableException("already started"));

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancel_unknownJob_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.cancel(id)).thenReturn(null);

        mockMvc.perform(post("/api/wristbands/jobs/" + id + "/cancel")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isNotFound());
    }

    @Test
    void jobPreview_returnsPng() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.getJob(id))
            .thenReturn(java.util.Optional.of(new PrintJob(id, r)));
        Mockito.when(wristbandZplResolver.resolve(Mockito.any())).thenReturn("^XA^XZ");
        Mockito.when(labelaryPreviewService.renderPreview(Mockito.any()))
            .thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/wristbands/jobs/" + id + "/preview")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void jobPreview_unknownJob_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(printQueueService.getJob(id)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/wristbands/jobs/" + id + "/preview")
                .header("X-API-Key", "test-key"))
            .andExpect(status().isNotFound());
    }

    @Test
    void printers_returnsConfiguredPrinters() throws Exception {
        when(printerRegistry.all()).thenReturn(List.of(
            new Printer("printer-1", "Inkom links", "http://w1:8080"),
            new Printer("printer-2", "Inkom rechts", "http://w2:8080")));

        mockMvc.perform(get("/api/wristbands/printers")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("printer-1"))
            .andExpect(jsonPath("$[0].displayName").value("Inkom links"))
            .andExpect(jsonPath("$[1].id").value("printer-2"))
            .andExpect(jsonPath("$[1].displayName").value("Inkom rechts"));
    }

    @Test
    void reprint_withPrinterId_targetsThatPrinter() throws Exception {
        UUID originalId = UUID.randomUUID();
        WristbandPrintRequest originalReq = sampleRequest();
        PrintJob originalJob = new PrintJob(originalId, originalReq);
        UUID newId = UUID.randomUUID();
        PrintJob newJob = new PrintJob(newId, sampleRequest(), "printer-2", "Second");

        when(printQueueService.getJob(originalId)).thenReturn(Optional.of(originalJob));
        when(printQueueService.enqueue(any())).thenReturn(newJob);

        mockMvc.perform(post("/api/wristbands/jobs/" + originalId + "/reprint?printerId=printer-2")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<com.stup.wristbandprinter.domain.PrintableRequest> captor =
            org.mockito.ArgumentCaptor.forClass(com.stup.wristbandprinter.domain.PrintableRequest.class);
        verify(printQueueService).enqueue(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPrinterId()).isEqualTo("printer-2");
    }

    @Test
    void reprint_withoutPrinterId_reusesOriginalRequest() throws Exception {
        UUID originalId = UUID.randomUUID();
        WristbandPrintRequest originalReq = sampleRequest();
        originalReq.setPrinterId("printer-1");
        PrintJob originalJob = new PrintJob(originalId, originalReq);
        UUID newId = UUID.randomUUID();
        PrintJob newJob = new PrintJob(newId, sampleRequest());

        when(printQueueService.getJob(originalId)).thenReturn(Optional.of(originalJob));
        when(printQueueService.enqueue(any())).thenReturn(newJob);

        mockMvc.perform(post("/api/wristbands/jobs/" + originalId + "/reprint")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<com.stup.wristbandprinter.domain.PrintableRequest> captor =
            org.mockito.ArgumentCaptor.forClass(com.stup.wristbandprinter.domain.PrintableRequest.class);
        verify(printQueueService).enqueue(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isSameAs(originalReq);
    }

    private WristbandPrintRequest sampleRequest() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }
}
