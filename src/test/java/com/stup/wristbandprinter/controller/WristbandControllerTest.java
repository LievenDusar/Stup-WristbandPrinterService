package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.domain.*;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class})
@TestPropertySource(properties = {"security.api-key=test-key"})
class WristbandControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean PrintQueueService printQueueService;
    @MockitoBean WristbandLayoutService wristbandLayoutService;
    @MockitoBean ZplGeneratorService zplGeneratorService;
    @MockitoBean LabelaryPreviewService labelaryPreviewService;

    private static final String API_KEY = "test-key";

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

    @Test
    void print_returns401_whenApiKeyWrong() throws Exception {
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isUnauthorized());
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
    void streamJobs_isAccessibleWithoutApiKey() throws Exception {
        when(printQueueService.subscribe()).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/wristbands/jobs/stream")
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk());
    }

    @Test
    void getJob_returnsFullDetailFields() throws Exception {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
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
        r.setAssociationName("STUP vzw");
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
