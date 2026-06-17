package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PrintableRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesCrewByDiscriminator() throws Exception {
        String json = """
            {"wristbandType":"crew","eventName":"E","firstName":"A","lastName":"B",
             "clubName":"C","barcodeValue":"123"}
            """;
        PrintableRequest req = mapper.readValue(json, PrintableRequest.class);
        assertThat(req).isInstanceOf(WristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.CREW);
    }

    @Test
    void deserializesPermitByDiscriminator() throws Exception {
        String json = """
            {"wristbandType":"permit","eventName":"E","permitLabel":"Elektriciteit"}
            """;
        PrintableRequest req = mapper.readValue(json, PrintableRequest.class);
        assertThat(req).isInstanceOf(PermitWristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.PERMIT);
    }

    @Test
    void serializedCrewIncludesLowercaseDiscriminator() throws Exception {
        WristbandPrintRequest req = new WristbandPrintRequest();
        req.setEventName("E"); req.setFirstName("A"); req.setLastName("B");
        req.setClubName("C"); req.setBarcodeValue("123");
        assertThat(mapper.writeValueAsString(req)).contains("\"wristbandType\":\"crew\"");
    }

    @Test
    void serializedPermitIncludesLowercaseDiscriminator() throws Exception {
        PermitWristbandPrintRequest req = new PermitWristbandPrintRequest();
        req.setEventName("E");
        req.setPermitLabel("Elektriciteit");
        assertThat(mapper.writeValueAsString(req)).contains("\"wristbandType\":\"permit\"");
    }

    @Test
    void missingDiscriminatorFails() {
        String json = "{\"eventName\":\"E\",\"firstName\":\"A\"}";
        assertThatThrownBy(() -> mapper.readValue(json, PrintableRequest.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void unknownDiscriminatorFails() {
        String json = "{\"wristbandType\":\"banana\",\"eventName\":\"E\"}";
        assertThatThrownBy(() -> mapper.readValue(json, PrintableRequest.class))
            .isInstanceOf(JsonProcessingException.class);
    }
}
