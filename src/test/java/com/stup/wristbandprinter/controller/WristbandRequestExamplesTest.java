package com.stup.wristbandprinter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.domain.FreeTextWristbandPrintRequest;
import com.stup.wristbandprinter.domain.PermitWristbandPrintRequest;
import com.stup.wristbandprinter.domain.PrintableRequest;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.domain.WristbandType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Swagger request-body examples: they must deserialize to the correct concrete type,
 * pass bean validation, and use a valid (non-zero) stock-color code. This is the exact bug class
 * that made the auto-generated example unusable.
 */
class WristbandRequestExamplesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void crewExampleDeserializesToValidCrewRequest() throws Exception {
        PrintableRequest req = mapper.readValue(WristbandRequestExamples.CREW, PrintableRequest.class);
        assertThat(req).isInstanceOf(WristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.CREW);
        assertThat(req.getStockColorCode()).isEqualTo(1);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void permitExampleDeserializesToValidPermitRequest() throws Exception {
        PrintableRequest req = mapper.readValue(WristbandRequestExamples.PERMIT, PrintableRequest.class);
        assertThat(req).isInstanceOf(PermitWristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.PERMIT);
        assertThat(req.getStockColorCode()).isEqualTo(1);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void freeTextExampleDeserializesToValidFreeTextRequest() throws Exception {
        PrintableRequest req = mapper.readValue(WristbandRequestExamples.FREETEXT, PrintableRequest.class);
        assertThat(req).isInstanceOf(FreeTextWristbandPrintRequest.class);
        assertThat(req.getWristbandType()).isEqualTo(WristbandType.FREETEXT);
        assertThat(req.getStockColorCode()).isEqualTo(1);
        assertThat(validator.validate(req)).isEmpty();
    }
}
