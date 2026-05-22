package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.LabelaryProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LabelaryPreviewServiceTest {

    @Test
    void renderPreview_returnsImageBytes() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/printers/8dpmm/labels/1x11/0/")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));

        LabelaryPreviewService service = buildService(template, "http://fake.labelary.com");
        byte[] result = service.renderPreview("^XA^XZ");

        assertThat(result).containsExactly(1, 2, 3);
        server.verify();
    }

    @Test
    void renderPreview_throwsLabelaryUnavailableException_onHttpError() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(template);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/printers")))
            .andRespond(withServerError());

        LabelaryPreviewService service = buildService(template, "http://fake.labelary.com");

        assertThatThrownBy(() -> service.renderPreview("^XA^XZ"))
            .isInstanceOf(LabelaryUnavailableException.class);
    }

    private LabelaryPreviewService buildService(RestTemplate template, String baseUrl) {
        LabelaryProperties labelaryProps = new LabelaryProperties();
        labelaryProps.setBaseUrl(baseUrl);
        labelaryProps.setTimeoutMs(3000);

        WristbandProperties wristbandProps = new WristbandProperties();
        wristbandProps.setDpi(203);

        return new LabelaryPreviewService(template, labelaryProps, wristbandProps);
    }
}
