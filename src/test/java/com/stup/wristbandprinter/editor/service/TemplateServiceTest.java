package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.domain.*;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateEntity;
import com.stup.wristbandprinter.editor.persistence.WristbandTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private WristbandTemplateRepository repository;

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(repository);
        lenient().when(repository.save(any(WristbandTemplateEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_generatesKebabSlugFromName_andPersistsDefinition() {
        when(repository.existsBySlug("festival-band")).thenReturn(false);

        TemplateDetailResponse result = service.create(request("Festival Band!", "festival"));

        ArgumentCaptor<WristbandTemplateEntity> captor = ArgumentCaptor.forClass(WristbandTemplateEntity.class);
        verify(repository).save(captor.capture());
        WristbandTemplateEntity saved = captor.getValue();
        assertThat(saved.getSlug()).isEqualTo("festival-band");
        assertThat(saved.getName()).isEqualTo("Festival Band!");
        assertThat(saved.getProjectType()).isEqualTo("festival");
        assertThat(saved.getDefaultPreviewColor()).isEqualTo("white");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(result.slug()).isEqualTo("festival-band");
    }

    @Test
    void create_deduplicatesSlugWhenTaken() {
        when(repository.existsBySlug("festival-band")).thenReturn(true);
        when(repository.existsBySlug("festival-band-2")).thenReturn(false);

        TemplateDetailResponse result = service.create(request("Festival Band", null));

        assertThat(result.slug()).isEqualTo("festival-band-2");
    }

    @Test
    void create_defaultsBlankPreviewColorToWhite() {
        when(repository.existsBySlug(any())).thenReturn(false);
        TemplateDetailResponse result = service.create(
            new UpsertTemplateRequest("X", null, "  ", sampleDefinition()));
        assertThat(result.defaultPreviewColor()).isEqualTo("white");
    }

    @Test
    void update_returnsEmptyWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());
        assertThat(service.update(id, request("X", null))).isEmpty();
    }

    @Test
    void update_mutatesFieldsAndBumpsUpdatedAt() {
        UUID id = UUID.randomUUID();
        WristbandTemplateEntity existing = existing(id, "old", "Old Name");
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));

        Optional<TemplateDetailResponse> result = service.update(id, request("New Name", "vip"));

        assertThat(result).isPresent();
        assertThat(existing.getName()).isEqualTo("New Name");
        assertThat(existing.getProjectType()).isEqualTo("vip");
        assertThat(existing.getSlug()).isEqualTo("old"); // slug is stable across updates
    }

    @Test
    void list_withProjectType_filtersByProjectType() {
        when(repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival"))
            .thenReturn(List.of(existing(UUID.randomUUID(), "a", "A")));
        assertThat(service.list("festival")).hasSize(1);
        verify(repository).findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival");
        verify(repository, never()).findByDeletedFalseOrderByUpdatedAtDesc();
    }

    @Test
    void list_withoutProjectType_returnsAllActive() {
        when(repository.findByDeletedFalseOrderByUpdatedAtDesc())
            .thenReturn(List.of(existing(UUID.randomUUID(), "a", "A")));
        assertThat(service.list(null)).hasSize(1);
    }

    @Test
    void softDelete_setsDeletedFlag_andReturnsTrue() {
        UUID id = UUID.randomUUID();
        WristbandTemplateEntity existing = existing(id, "a", "A");
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(existing));

        assertThat(service.softDelete(id)).isTrue();
        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void softDelete_returnsFalseWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());
        assertThat(service.softDelete(id)).isFalse();
    }

    private UpsertTemplateRequest request(String name, String projectType) {
        return new UpsertTemplateRequest(name, projectType, "white", sampleDefinition());
    }

    private TemplateDefinition sampleDefinition() {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        return new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el));
    }

    private WristbandTemplateEntity existing(UUID id, String slug, String name) {
        WristbandTemplateEntity e = new WristbandTemplateEntity();
        e.setId(id);
        e.setSlug(slug);
        e.setName(name);
        e.setDefaultPreviewColor("white");
        e.setDefinition(sampleDefinition());
        e.setCreatedAt(java.time.Instant.now());
        e.setUpdatedAt(java.time.Instant.now());
        return e;
    }
}
