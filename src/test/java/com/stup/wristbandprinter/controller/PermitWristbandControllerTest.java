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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PermitWristbandController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties({AdminProperties.class, WristbandProperties.class})
@TestPropertySource(properties = {
    "security.api-key=test-key",
    "security.admin.password=pw",
    "wristband.stock-colors[2]=#800080"
})
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
    void permitPrint_returns400_whenCopiesBelowOne() throws Exception {
        String body = """
            {
              "eventName": "Pukkelpop 2026",
              "permitLabel": "Elektriciteit",
              "copies": 0
            }
            """;

        mockMvc.perform(post("/api/wristbands/permit/print")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
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
        req.setStockColorCode(2);

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
