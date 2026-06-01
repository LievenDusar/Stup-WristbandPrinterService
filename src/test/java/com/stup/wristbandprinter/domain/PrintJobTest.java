package com.stup.wristbandprinter.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrintJobTest {

    @Test
    void toDetailResponse_includesAllWristbandFields() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobDetailResponse detail = job.toDetailResponse();

        assertThat(detail.jobId()).isEqualTo(job.getJobId());
        assertThat(detail.status()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(detail.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(detail.firstName()).isEqualTo("Jan");
        assertThat(detail.lastName()).isEqualTo("Janssens");
        assertThat(detail.associationName()).isEqualTo("STUP vzw");
        assertThat(detail.barcodeValue()).isEqualTo("123456789");
        assertThat(detail.submittedAt()).isEqualTo(job.getSubmittedAt());
    }

    @Test
    void toResponse_includesName() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(java.util.UUID.randomUUID(), r);

        PrintJobResponse resp = job.toResponse();

        assertThat(resp.firstName()).isEqualTo("Jan");
        assertThat(resp.lastName()).isEqualTo("Janssens");
        assertThat(resp.eventName()).isEqualTo("Pukkelpop 2026");
    }
}
