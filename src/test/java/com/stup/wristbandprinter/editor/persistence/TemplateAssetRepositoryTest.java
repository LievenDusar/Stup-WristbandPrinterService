package com.stup.wristbandprinter.editor.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TemplateAssetRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TemplateAssetRepository repository;

    @Test
    void persistsAndReadsBackPngBytes() {
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(UUID.randomUUID());
        e.setName("logo.png");
        e.setPng(new byte[]{1, 2, 3, 4});
        e.setWidth(100);
        e.setHeight(50);
        e.setCreatedAt(Instant.now());

        UUID id = repository.save(e).getId();

        TemplateAssetEntity loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("logo.png");
        assertThat(loaded.getPng()).containsExactly(1, 2, 3, 4);
        assertThat(loaded.getWidth()).isEqualTo(100);
    }
}
