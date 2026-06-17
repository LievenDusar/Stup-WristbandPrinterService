package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WristbandTypeJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesToLowercase() throws Exception {
        assertThat(mapper.writeValueAsString(WristbandType.CREW)).isEqualTo("\"crew\"");
        assertThat(mapper.writeValueAsString(WristbandType.PERMIT)).isEqualTo("\"permit\"");
    }

    @Test
    void deserializesLowercase() throws Exception {
        assertThat(mapper.readValue("\"crew\"", WristbandType.class)).isEqualTo(WristbandType.CREW);
        assertThat(mapper.readValue("\"permit\"", WristbandType.class)).isEqualTo(WristbandType.PERMIT);
    }

    @Test
    void deserializesAnyCaseForRobustness() throws Exception {
        assertThat(mapper.readValue("\"CREW\"", WristbandType.class)).isEqualTo(WristbandType.CREW);
        assertThat(mapper.readValue("\"Permit\"", WristbandType.class)).isEqualTo(WristbandType.PERMIT);
    }

    @Test
    void deserializeUnknownValueFails() {
        assertThatThrownBy(() -> mapper.readValue("\"banana\"", WristbandType.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void deserializeJsonNullYieldsNull() throws Exception {
        assertThat(mapper.readValue("null", WristbandType.class)).isNull();
    }
}
