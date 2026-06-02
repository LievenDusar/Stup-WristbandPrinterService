package com.stup.wristbandprinter.editor.persistence;

import com.stup.wristbandprinter.editor.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class WristbandTemplateRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WristbandTemplateRepository repository;

    @Test
    void persistsAndReadsBackJsonDefinition() {
        WristbandTemplateEntity saved = repository.save(entity("Festival Band", "festival-band", "festival"));

        WristbandTemplateEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Festival Band");
        assertThat(loaded.getDefinition().canvas().widthDots()).isEqualTo(203);
        assertThat(loaded.getDefinition().elements()).hasSize(1);
        assertThat(loaded.getDefinition().elements().get(0).binding()).isEqualTo(DataBinding.FULL_NAME);
    }

    @Test
    void findByProjectTypeAndDeletedFalse_filtersCorrectly() {
        repository.save(entity("A", "a", "festival"));
        repository.save(entity("B", "b", "conference"));
        WristbandTemplateEntity deleted = entity("C", "c", "festival");
        deleted.setDeleted(true);
        repository.save(deleted);

        List<WristbandTemplateEntity> result =
            repository.findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc("festival");

        assertThat(result).extracting(WristbandTemplateEntity::getName).containsExactly("A");
    }

    @Test
    void findBySlugAndDeletedFalse_returnsActiveOnly() {
        repository.save(entity("A", "my-slug", null));
        assertThat(repository.findBySlugAndDeletedFalse("my-slug")).isPresent();
        assertThat(repository.findBySlugAndDeletedFalse("missing")).isEmpty();
    }

    private WristbandTemplateEntity entity(String name, String slug, String projectType) {
        TemplateElement el = new TemplateElement(
            "el-1", ElementType.TEXT, 40, 120, 28, 600, 90,
            DataBinding.FULL_NAME, null, 28, "0", null, null, null, null, null);
        TemplateDefinition def = new TemplateDefinition(new Canvas(203, 2233, 300), List.of(el));

        WristbandTemplateEntity e = new WristbandTemplateEntity();
        e.setId(UUID.randomUUID());
        e.setSlug(slug);
        e.setName(name);
        e.setProjectType(projectType);
        e.setDefaultPreviewColor("white");
        e.setDefinition(def);
        e.setGeneratedZpl(null);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setDeleted(false);
        return e;
    }
}
