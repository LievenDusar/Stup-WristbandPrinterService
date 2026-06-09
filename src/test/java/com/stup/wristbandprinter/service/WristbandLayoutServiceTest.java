package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WristbandLayoutServiceTest {

    private WristbandLayoutService service;

    @BeforeEach
    void setUp() {
        service = new WristbandLayoutService();
    }

    @Test
    void buildData_mapsAllFieldsFromRequest() {
        WristbandPrintRequest request = new WristbandPrintRequest();
        request.setEventName("Pukkelpop 2026");
        request.setFirstName("Jan");
        request.setLastName("Janssens");
        request.setAssociationName("STUP vzw");
        request.setBarcodeValue("123456789");

        WristbandData data = service.buildData(request);

        assertThat(data.eventName()).isEqualTo("Pukkelpop 2026");
        assertThat(data.firstName()).isEqualTo("Jan");
        assertThat(data.lastName()).isEqualTo("Janssens");
        assertThat(data.associationName()).isEqualTo("STUP vzw");
        assertThat(data.barcodeValue()).isEqualTo("123456789");
    }

    @Test
    void buildData_defaultsCodeSymbologyToCode128_whenNullOnRequest() {
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("E"); req.setFirstName("F"); req.setLastName("L");
        req.setAssociationName("A"); req.setBarcodeValue("123");
        // codeSymbology left null

        WristbandData data = service.buildData(req);

        assertThat(data.codeSymbology()).isEqualTo(CodeSymbology.CODE128);
    }

    @Test
    void buildData_preservesCodeSymbologyFromRequest() {
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("E"); req.setFirstName("F"); req.setLastName("L");
        req.setAssociationName("A"); req.setBarcodeValue("123");
        req.setCodeSymbology(CodeSymbology.QR);

        WristbandData data = service.buildData(req);

        assertThat(data.codeSymbology()).isEqualTo(CodeSymbology.QR);
    }
}
