package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Worker -> management self-registration calls. Failures are logged, never thrown
 *  (a worker that can't reach management must still serve prints). */
@Component
@Profile("worker")
public class ManagementClient {

    private static final Logger log = LoggerFactory.getLogger(ManagementClient.class);

    private final String apiKey;
    private final RestClient restClient;
    private final WorkerRegistrationProperties props;

    public ManagementClient(@Value("${security.api-key}") String apiKey,
                            RestClient.Builder builder,
                            WorkerRegistrationProperties props) {
        this.apiKey = apiKey;
        this.restClient = builder.build();
        this.props = props;
    }

    public void register() {
        if (!StringUtils.hasText(props.getId()) || !StringUtils.hasText(props.getManagementBaseUrl())) {
            log.warn("Worker self-registration skipped: worker.id / worker.management-base-url not configured");
            return;
        }
        try {
            restClient.post()
                .uri(props.getManagementBaseUrl() + "/api/internal/printers/register")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegisterPrinterRequest(props.getId(), props.getDisplayName(), props.getBaseUrl()))
                .retrieve()
                .toBodilessEntity();
            log.debug("Registered worker {} with management", props.getId());
        } catch (RestClientException e) {
            log.warn("Worker self-registration to {} failed: {}", props.getManagementBaseUrl(), e.getMessage());
        }
    }

    public void deregister() {
        if (!StringUtils.hasText(props.getId()) || !StringUtils.hasText(props.getManagementBaseUrl())) {
            return;
        }
        try {
            restClient.post()
                .uri(props.getManagementBaseUrl() + "/api/internal/printers/" + props.getId() + "/deregister")
                .header("X-API-Key", apiKey)
                .retrieve()
                .toBodilessEntity();
            log.debug("Deregistered worker {} from management", props.getId());
        } catch (RestClientException e) {
            log.warn("Worker deregistration to {} failed: {}", props.getManagementBaseUrl(), e.getMessage());
        }
    }
}
