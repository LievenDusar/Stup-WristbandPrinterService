package com.stup.wristbandprinter.editor.controller;

import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.service.TemplateService;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WristbandAssetController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class WristbandAssetControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TemplateService templateService;

    private static final String API_KEY = "test-key";

    @Test
    void uploadAsset_returns201WithAssetId() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.storeAsset(eq("logo.png"), any()))
            .thenReturn(new AssetResponse(assetId, "logo.png", 40, 20));

        var file = new MockMultipartFile("file", "logo.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/wristband-assets").file(file).header("X-API-Key", API_KEY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(assetId.toString()));
    }

    @Test
    void getAsset_returnsPng() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.rawAsset(eq(assetId))).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/wristband-assets/" + assetId).header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void getAsset_returns404_whenMissing() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.rawAsset(eq(assetId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/wristband-assets/" + assetId).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }
}
