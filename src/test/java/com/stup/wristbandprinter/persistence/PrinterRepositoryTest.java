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
}
