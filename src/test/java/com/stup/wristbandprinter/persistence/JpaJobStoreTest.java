package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
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

    @Test
    void saveAndLoad_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        Instant submitted = Instant.now();
        store.save(PrintJob.restore(id, request(), PrintJobStatus.DONE, submitted, submitted, null));

        List<PrintJob> loaded = store.loadAll();

        assertThat(loaded).hasSize(1);
        PrintJob job = loaded.get(0);
        assertThat(job.getJobId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
        assertThat(job.getRequest().getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(job.getRequest().getBarcodeValue()).isEqualTo("123456789");
    }

    @Test
    void deleteCompleted_removesDoneAndFailedButKeepsPending() {
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.DONE, Instant.now(), Instant.now(), null));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.FAILED, Instant.now(), Instant.now(), "boom"));
        store.save(PrintJob.restore(UUID.randomUUID(), request(), PrintJobStatus.PENDING, Instant.now(), null, null));

        store.deleteCompleted();

        List<PrintJob> remaining = store.loadAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getStatus()).isEqualTo(PrintJobStatus.PENDING);
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
