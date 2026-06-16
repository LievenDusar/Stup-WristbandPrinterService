package com.stup.wristbandprinter.editor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.config.AdminProperties;
import com.stup.wristbandprinter.config.SecurityConfig;
import com.stup.wristbandprinter.editor.domain.*;
import com.stup.wristbandprinter.editor.service.TemplateService;
import com.stup.wristbandprinter.security.ApiKeyAuthFilter;
import com.stup.wristbandprinter.security.AuthCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TemplateController.class)
@Import({SecurityConfig.class, ApiKeyAuthFilter.class, AuthCookieService.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = {"security.api-key=test-key", "security.admin.password=pw"})
class TemplateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean TemplateService templateService;

    private static final String API_KEY = "test-key";

    @Test
    void create_returns201WithDetail() throws Exception {
        TemplateDetailResponse detail = detail(UUID.randomUUID(), "festival-band");
        when(templateService.create(any())).thenReturn(detail);

        mockMvc.perform(post("/api/wristband-templates")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("Festival Band"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("festival-band"));
    }

    @Test
    void create_returns400_whenNameBlank() throws Exception {
        mockMvc.perform(post("/api/wristband-templates")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(""))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void create_returns401_withoutApiKey() throws Exception {
        mockMvc.perform(post("/api/wristband-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("X"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.getById(id)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/wristband-templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_returns200WithDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.getById(id)).thenReturn(Optional.of(detail(id, "slug-1")));
        mockMvc.perform(get("/api/wristband-templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void list_passesProjectTypeFilter() throws Exception {
        when(templateService.list("festival"))
            .thenReturn(List.of(new TemplateSummaryResponse(
                UUID.randomUUID(), "a", "A", "festival", Instant.now())));

        mockMvc.perform(get("/api/wristband-templates?projectType=festival").header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].projectType").value("festival"));

        verify(templateService).list("festival");
    }

    @Test
    void update_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.update(eq(id), any())).thenReturn(Optional.empty());
        mockMvc.perform(put("/api/wristband-templates/" + id)
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("New"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204_whenDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.softDelete(id)).thenReturn(true);
        mockMvc.perform(delete("/api/wristband-templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.softDelete(id)).thenReturn(false);
        mockMvc.perform(delete("/api/wristband-templates/" + id).header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void preview_returnsPngWithSampleData() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), eq("red")))
            .thenReturn(Optional.of(new byte[]{1, 2, 3}));

        mockMvc.perform(post("/api/wristband-templates/" + id + "/preview?color=red")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void preview_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(templateService.renderPreview(eq(id), isNull(), any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/wristband-templates/" + id + "/preview")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isNotFound());
    }

    @Test
    void uploadAsset_returns201WithAssetId() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(templateService.storeAsset(eq("logo.png"), any()))
            .thenReturn(new com.stup.wristbandprinter.editor.domain.AssetResponse(assetId, "logo.png", 40, 20));

        var file = new org.springframework.mock.web.MockMultipartFile(
            "file", "logo.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/wristband-templates/assets").file(file).header("X-API-Key", API_KEY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(assetId.toString()));
    }

    private UpsertTemplateRequest request(String name) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new UpsertTemplateRequest(name, "festival", "white",
            new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el)));
    }

    private TemplateDetailResponse detail(UUID id, String slug) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new TemplateDetailResponse(id, slug, "Festival Band", "festival", "white",
            new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el)), null, Instant.now());
    }
}
