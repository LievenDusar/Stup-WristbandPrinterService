package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.controller.AuthController;
import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandLayoutService;
import com.stup.wristbandprinter.service.ZplGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WristbandController.class, AuthController.class})
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PrintQueueService printQueueService;
    @MockitoBean private WristbandLayoutService wristbandLayoutService;
    @MockitoBean private ZplGeneratorService zplGeneratorService;
    @MockitoBean private LabelaryPreviewService labelaryPreviewService;

    @Test
    void sseStream_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/wristbands/jobs/stream"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpoint_isPublic() throws Exception {
        // permitAll: a GET reaches dispatch (the mapping is POST-only) instead of being
        // rejected by security with 401. The app's GlobalExceptionHandler maps the resulting
        // HttpRequestMethodNotSupportedException to 500, so the key assertion is "NOT 401".
        mockMvc.perform(get("/api/wristbands/login"))
            .andExpect(status().is(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }
}
