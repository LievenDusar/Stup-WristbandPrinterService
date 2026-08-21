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
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobDetailResponse detail = job.toDetailResponse();

        assertThat(detail.jobId()).isEqualTo(job.getJobId());
        assertThat(detail.status()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(detail.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(detail.firstName()).isEqualTo("Jan");
        assertThat(detail.lastName()).isEqualTo("Janssens");
        assertThat(detail.clubName()).isEqualTo("STUP vzw");
        assertThat(detail.barcodeValue()).isEqualTo("123456789");
        assertThat(detail.submittedAt()).isEqualTo(job.getSubmittedAt());
    }

    @Test
    void toResponse_includesName() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setClubName("STUP vzw");
        r.setBarcodeValue("123456789");
        PrintJob job = new PrintJob(java.util.UUID.randomUUID(), r);

        PrintJobResponse resp = job.toResponse();

        assertThat(resp.firstName()).isEqualTo("Jan");
        assertThat(resp.lastName()).isEqualTo("Janssens");
        assertThat(resp.eventName()).isEqualTo("Pukkelpop 2026");
    }

    @Test
    void toResponse_usesPermitLabelForPermitBands() {
        PermitWristbandPrintRequest r = new PermitWristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setPermitLabel("Elektriciteit");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobResponse resp = job.toResponse();

        assertThat(resp.wristbandType()).isEqualTo(WristbandType.PERMIT);
        assertThat(resp.permitLabel()).isEqualTo("Elektriciteit");
        assertThat(resp.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(resp.firstName()).isNull();
        assertThat(resp.lastName()).isNull();
    }

    @Test
    void toResponse_usesFreeTextForFreeTextBands() {
        FreeTextWristbandPrintRequest r = new FreeTextWristbandPrintRequest();
        r.setText("Backstage");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobResponse resp = job.toResponse();

        assertThat(resp.wristbandType()).isEqualTo(WristbandType.FREETEXT);
        assertThat(resp.freeText()).isEqualTo("Backstage");
        assertThat(resp.eventName()).isNull();
        assertThat(resp.firstName()).isNull();
        assertThat(resp.lastName()).isNull();
        assertThat(resp.permitLabel()).isNull();
    }

    @Test
    void toDetailResponse_usesFreeTextForFreeTextBands() {
        FreeTextWristbandPrintRequest r = new FreeTextWristbandPrintRequest();
        r.setText("Backstage");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        PrintJobDetailResponse detail = job.toDetailResponse();

        assertThat(detail.wristbandType()).isEqualTo(WristbandType.FREETEXT);
        assertThat(detail.freeText()).isEqualTo("Backstage");
        assertThat(detail.eventName()).isNull();
    }

    @Test
    void responses_carryCopies() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setClubName("A"); r.setBarcodeValue("B");
        r.setCopies(25);
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        assertThat(job.toResponse().copies()).isEqualTo(25);
        assertThat(job.toDetailResponse().copies()).isEqualTo(25);
    }

    @Test
    void responses_defaultCopiesToOne() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setClubName("A"); r.setBarcodeValue("B");
        PrintJob job = new PrintJob(UUID.randomUUID(), r);

        assertThat(job.toResponse().copies()).isEqualTo(1);
    }

    @Test
    void toResponse_and_toDetailResponse_carryPrinterIdentity() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("E"); r.setFirstName("F"); r.setLastName("L");
        r.setClubName("A"); r.setBarcodeValue("B");
        PrintJob job = new PrintJob(java.util.UUID.randomUUID(), r, "printer-1", "Inkom links");
        assertThat(job.toResponse().printerId()).isEqualTo("printer-1");
        assertThat(job.toResponse().printerName()).isEqualTo("Inkom links");
        assertThat(job.toDetailResponse().printerId()).isEqualTo("printer-1");
        assertThat(job.toDetailResponse().printerName()).isEqualTo("Inkom links");
    }
}
