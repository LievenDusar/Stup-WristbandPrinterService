package com.stup.wristbandprinter.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.exception.GlobalExceptionHandler;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// TODO: the excludeFilters below can be removed once SecurityConfig / ApiKeyAuthFilter /
// AuthCookieService are gated with @Profile("!worker") — they won't load under "worker" then.
@WebMvcTest(
    value = WorkerPrintController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class}
    )
)
@Import({WorkerSecurityConfig.class, WorkerApiKeyFilter.class, GlobalExceptionHandler.class})
@ActiveProfiles("worker")
@TestPropertySource(properties = "security.api-key=test-key")
class WorkerPrintControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    PrinterService printerService;

    @Autowired
    ObjectMapper mapper;

    private String body() throws Exception {
        return mapper.writeValueAsString(
            new PrintForwardRequest(UUID.randomUUID(), "^XA^FDhi^FS^XZ"));
    }

    @Test
    void printsAndReturns200WithValidApiKey() throws Exception {
        mvc.perform(post("/api/internal/print")
                .header("X-API-Key", "test-key")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isOk());
        verify(printerService).send("^XA^FDhi^FS^XZ");
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mvc.perform(post("/api/internal/print")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(printerService);
    }

    @Test
    void mapsPrinterUnavailableTo503() throws Exception {
        doThrow(new PrinterUnavailableException("printer down"))
            .when(printerService).send(Mockito.anyString());
        mvc.perform(post("/api/internal/print")
                .header("X-API-Key", "test-key")
                .contentType("application/json")
                .content(body()))
            .andExpect(status().isServiceUnavailable());
    }
}
