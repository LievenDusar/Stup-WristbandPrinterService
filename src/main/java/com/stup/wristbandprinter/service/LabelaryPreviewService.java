package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.LabelaryProperties;
import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LabelaryUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Profile("!worker")
@Service
public class LabelaryPreviewService {

    private static final Logger log = LoggerFactory.getLogger(LabelaryPreviewService.class);

    private final RestTemplate restTemplate;
    private final LabelaryProperties labelaryProps;
    private final int dpmm;  // dots per mm derived from configured DPI

    @Autowired
    public LabelaryPreviewService(LabelaryProperties labelaryProps, WristbandProperties wristbandProps) {
        this.restTemplate = new RestTemplate();
        this.labelaryProps = labelaryProps;
        this.dpmm = Math.round(wristbandProps.getDpi() / 25.4f);
    }

    // Package-private constructor for tests — accepts a pre-configured RestTemplate
    LabelaryPreviewService(RestTemplate restTemplate, LabelaryProperties labelaryProps,
                           WristbandProperties wristbandProps) {
        this.restTemplate = restTemplate;
        this.labelaryProps = labelaryProps;
        this.dpmm = Math.round(wristbandProps.getDpi() / 25.4f);
    }

    public byte[] renderPreview(String zpl) {
        return renderPreview(zpl, 1.0, 11.0, dpmm);
    }

    public byte[] renderPreview(String zpl, double widthInches, double heightInches, int dpmm) {
        String url = labelaryProps.getBaseUrl()
            + "/v1/printers/{dpmm}dpmm/labels/{width}x{height}/0/";

        log.info("Requesting Labelary preview at {}dpmm ({}x{} in)", dpmm, widthInches, heightInches);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(zpl, headers);

            byte[] result = restTemplate.postForObject(url, entity, byte[].class,
                dpmm, trim(widthInches), trim(heightInches));

            if (result == null) {
                throw new LabelaryUnavailableException("Labelary returned empty response");
            }
            return result;
        } catch (RestClientException e) {
            throw new LabelaryUnavailableException("Labelary API unavailable: " + e.getMessage(), e);
        }
    }

    /** Labelary wants compact inch values: "1" not "1.0", "0.68" not "0.680000". */
    private static String trim(double inches) {
        if (inches == Math.rint(inches)) {
            return Integer.toString((int) inches);
        }
        return java.math.BigDecimal.valueOf(inches)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }
}
