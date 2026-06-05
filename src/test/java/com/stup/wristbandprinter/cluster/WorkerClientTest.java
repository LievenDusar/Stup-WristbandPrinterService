package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkerClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private WorkerClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WorkerClient("test-key", builder);
    }

    @Test
    void print_postsForwardRequestWithApiKey() {
        UUID jobId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        server.expect(requestTo("http://worker:8080/api/internal/print"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.zpl").value("^XA^XZ"))
            .andRespond(withSuccess());

        assertThatCode(() -> client.print("http://worker:8080", jobId, "^XA^XZ"))
            .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void print_throwsPrinterUnavailable_onErrorResponse() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://worker:8080/api/internal/print"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.print("http://worker:8080", jobId, "^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class)
            .hasMessageContaining("http://worker:8080");
    }

    @Test
    void print_throwsPrinterUnavailable_onConnectionFailure() {
        server.expect(requestTo("http://worker:8080/api/internal/print"))
            .andRespond(request -> { throw new ResourceAccessException("Connection refused"); });

        assertThatThrownBy(() -> client.print("http://worker:8080", UUID.randomUUID(), "^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class);
    }
}
