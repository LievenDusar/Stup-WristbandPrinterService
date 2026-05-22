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
