package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.controller.AuthController;
import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.editor.service.PreviewColorService;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandGalleryCatalog;
import com.stup.wristbandprinter.service.WristbandZplResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WristbandController.class, AuthController.class})
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties({AdminProperties.class, WristbandProperties.class, CorsProperties.class})
@TestPropertySource(properties = {
    "security.api-key=test-key",
    "security.print-api-key=print-key",
    "security.admin.password=pw",
    "cors.allowed-origins=https://app.example"
})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PrintQueueService printQueueService;
    @MockitoBean private WristbandZplResolver wristbandZplResolver;
    @MockitoBean private LabelaryPreviewService labelaryPreviewService;
    @MockitoBean private com.stup.wristbandprinter.cluster.PrinterRegistry printerRegistry;
    @MockitoBean private PreviewColorService previewColorService;
    @MockitoBean private WristbandGalleryCatalog wristbandGalleryCatalog;

    @Test
    void sseStream_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/wristbands/jobs/stream"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void templateEditorPage_isPublic() throws Exception {
        // permitAll → request passes the security filter (no 401). Whether the static
        // resource is then served (200) or unmapped in the slice (404), it must not be 401.
        mockMvc.perform(get("/template-editor.html"))
            .andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()));
    }

    @Test
    void loginEndpoint_isPublic() throws Exception {
        // permitAll: a GET reaches dispatch (the mapping is POST-only) and yields 405
        // Method Not Allowed, rather than being rejected by security with 401.
        mockMvc.perform(get("/api/wristbands/login"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void corsPreflight_fromAllowedOrigin_passesWithoutAuth() throws Exception {
        // Browser preflight carries no key; it must return 200 with the allow-origin header,
        // otherwise the real cross-origin POST is never sent.
        mockMvc.perform(options("/api/wristbands/print")
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));
    }

    @Test
    void printEndpoint_acceptsPrintOnlyKey() throws Exception {
        // ROLE_PRINT reaches /print: past security it hits body validation (400), not auth (401).
        mockMvc.perform(post("/api/wristbands/print")
                .header("X-API-Key", "print-key")
                .contentType("application/json")
                .content("{}"))
            .andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()));
    }

    @Test
    void adminEndpoint_rejectsPrintOnlyKey() throws Exception {
        // The print-only key must NOT reach admin endpoints (job list).
        mockMvc.perform(get("/api/wristbands/jobs").header("X-API-Key", "print-key"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_acceptsAdminKey() throws Exception {
        mockMvc.perform(get("/api/wristbands/jobs").header("X-API-Key", "test-key"))
            .andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()));
    }
}
