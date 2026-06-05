package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/** Forwards a rendered print job from management to a printer-worker over HTTP. */
@Component
@Profile("!worker")
public class WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);

    private final String apiKey;
    private final RestClient restClient;

    public WorkerClient(@Value("${security.api-key}") String apiKey, RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.restClient = builder.build();
    }

    /**
     * Send the job to the worker at {@code baseUrl} and block until it finishes.
     * A non-2xx response or any transport failure is surfaced as PrinterUnavailableException
     * so the queue worker marks the job FAILED, exactly as a local printer failure would.
     */
    public void print(String baseUrl, UUID jobId, String zpl) {
        try {
            restClient.post()
                .uri(baseUrl + "/api/internal/print")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PrintForwardRequest(jobId, zpl))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Forward to worker {} failed for job {}: {}", baseUrl, jobId, e.getMessage());
            throw new PrinterUnavailableException(
                "Worker at " + baseUrl + " could not print job " + jobId + ": " + e.getMessage(), e);
        }
    }
}
