package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {
    "security.api-key=test-key",
    "security.admin.username=admin",
    "security.admin.password=s3cret",
    "security.cookie-secret=test-signing-secret",
    "security.cookie.secure=false",
    "security.cookie.same-site=Lax"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void login_correctCredentials_setsCookie() throws Exception {
        mockMvc.perform(post("/api/wristbands/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"s3cret\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("stup_admin=")))
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/wristbands/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_clearsCookie() throws Exception {
        mockMvc.perform(post("/api/wristbands/logout"))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Set-Cookie",
                org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
