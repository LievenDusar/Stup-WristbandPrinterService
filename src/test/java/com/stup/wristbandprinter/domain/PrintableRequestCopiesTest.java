package com.stup.wristbandprinter.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintableRequestCopiesTest {

    @Test
    void crew_defaultsToOne_whenCopiesNull() {
        assertThat(new WristbandPrintRequest().getCopies()).isEqualTo(1);
    }

    @Test
    void permit_defaultsToOne_whenCopiesNull() {
        assertThat(new PermitWristbandPrintRequest().getCopies()).isEqualTo(1);
    }

    @Test
    void crew_returnsSetValue() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setCopies(42);
        assertThat(r.getCopies()).isEqualTo(42);
    }

    @Test
    void withCopies_returnsCopyWithNewCount_preservingOtherFields() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setBarcodeValue("123");
        r.setCopies(3);

        PrintableRequest updated = r.withCopies(120);

        assertThat(updated.getCopies()).isEqualTo(120);
        assertThat(((WristbandPrintRequest) updated).getEventName()).isEqualTo("Pukkelpop 2026");
        assertThat(((WristbandPrintRequest) updated).getBarcodeValue()).isEqualTo("123");
        assertThat(r.getCopies()).isEqualTo(3); // original untouched
    }

    @Test
    void withPrinterId_carriesCopiesThrough() {
        PermitWristbandPrintRequest r = new PermitWristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setPermitLabel("Elektriciteit");
        r.setCopies(7);

        PrintableRequest stamped = r.withPrinterId("printer-2");

        assertThat(stamped.getCopies()).isEqualTo(7);
        assertThat(stamped.getPrinterId()).isEqualTo("printer-2");
    }
}
