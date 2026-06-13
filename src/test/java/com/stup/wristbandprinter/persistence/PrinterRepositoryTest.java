package com.stup.wristbandprinter.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrinterRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PrinterRepository repository;

    @Test
    void saveAndLoad_roundTripsPrinter() {
        repository.save(new PrinterEntity("printer-7", "Inkom links", "http://printer-7:8080"));

        Optional<PrinterEntity> found = repository.findById("printer-7");

        assertThat(found).isPresent();
        PrinterEntity p = found.get();
        assertThat(p.getDisplayName()).isEqualTo("Inkom links");
        assertThat(p.getBaseUrl()).isEqualTo("http://printer-7:8080");
        assertThat(p.isOnline()).isFalse();
        assertThat(p.isHidden()).isFalse();
        assertThat(p.isDefault()).isFalse();
        assertThat(p.getRegisteredAt()).isNotNull();
    }

    @Test
    void defaultResolutionQueries_orderByRegisteredAtAndFilter() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-01T00:00:00Z");
        PrinterEntity a = new PrinterEntity("a", "A", "http://a:8080"); a.setRegisteredAt(t0);
        PrinterEntity b = new PrinterEntity("b", "B", "http://b:8080"); b.setRegisteredAt(t0.plusSeconds(60)); b.setOnline(true);
        repository.saveAll(java.util.List.of(a, b));

        assertThat(repository.findByIsDefaultTrue()).isEmpty();
        assertThat(repository.findFirstByHiddenFalseOrderByRegisteredAtAscIdAsc())
            .map(PrinterEntity::getId).contains("a");
        assertThat(repository.findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAscIdAsc())
            .map(PrinterEntity::getId).contains("b");

        a.setDefault(true);
        repository.save(a);
        assertThat(repository.findByIsDefaultTrue()).map(PrinterEntity::getId).contains("a");
    }
}
