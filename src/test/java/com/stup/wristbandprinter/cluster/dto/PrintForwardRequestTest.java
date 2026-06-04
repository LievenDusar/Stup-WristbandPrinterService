package com.stup.wristbandprinter.cluster.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrintForwardRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesRoundTrip() throws Exception {
        UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PrintForwardRequest original = new PrintForwardRequest(jobId, "^XA^FDhi^FS^XZ");

        String json = mapper.writeValueAsString(original);
        PrintForwardRequest parsed = mapper.readValue(json, PrintForwardRequest.class);

        assertThat(parsed.jobId()).isEqualTo(jobId);
        assertThat(parsed.zpl()).isEqualTo("^XA^FDhi^FS^XZ");
    }
}
