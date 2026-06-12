package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.domain.PrintableRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaJobStore.class)
@Testcontainers
class JpaJobStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JpaJobStore store;

    @Autowired
    private PrintJobRepository repository;

    @Test
    void saveAndLoad_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        Instant submitted = Instant.now();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, submitted, submitted, null));

        List<PrintJob> loaded = store.loadActive();

        assertThat(loaded).hasSize(1);
        PrintJob job = loaded.get(0);
        assertThat(job.getJobId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
        assertThat(job.getRequest()).isInstanceOf(WristbandPrintRequest.class);
        WristbandPrintRequest req = (WristbandPrintRequest) job.getRequest();
        assertThat(req.getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(req.getBarcodeValue()).isEqualTo("123456789");
    }

    @Test
    void softDeleteCompleted_flagsTerminalRowsButKeepsThem() {
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.FAILED, Instant.now(), Instant.now(), "boom"));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.PENDING, Instant.now(), null, null));

        store.softDeleteCompleted();

        List<PrintJob> active = store.loadActive();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(repository.count()).isEqualTo(3); // rows still present (soft, not hard, delete)
    }

    @Test
    void deleteById_hardRemovesRow() {
        UUID id = UUID.randomUUID();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.deleteById(id);
        assertThat(repository.count()).isZero();
    }

    @Test
    void save_persistsPrinterIdentity() {
        UUID id = UUID.randomUUID();
        store.save(PrintJob.restore(id, request(), "printer-1", "Inkom links",
            PrintJobStatus.DONE, Instant.now(), Instant.now(), null));

        PrintJob loaded = store.loadActive().stream()
            .filter(j -> j.getJobId().equals(id)).findFirst().orElseThrow();
        assertThat(loaded.getPrinterId()).isEqualTo("printer-1");
        assertThat(loaded.getPrinterName()).isEqualTo("Inkom links");
    }

    @Test
    void saveAndLoad_roundTripsCopies() {
        UUID id = UUID.randomUUID();
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("Pukkelpop 2026");
        req.setFirstName("Jan");
        req.setLastName("Janssens");
        req.setAssociationName("STUP vzw");
        req.setBarcodeValue("123456789");
        req.setCopies(120);
        Instant now = Instant.now();
        store.save(PrintJob.restore(id, req, PrintJobStatus.DONE, now, now, null));

        PrintJob loaded = store.loadActive().get(0);

        assertThat(loaded.getRequest().getCopies()).isEqualTo(120);
    }

    private WristbandPrintRequest request() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }
}
