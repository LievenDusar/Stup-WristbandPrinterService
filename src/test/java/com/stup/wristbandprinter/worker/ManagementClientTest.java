package com.stup.wristbandprinter.worker;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ManagementClientTest {

    private ManagementClient client(WorkerRegistrationProperties props, MockRestServiceServer[] out) {
        RestClient.Builder builder = RestClient.builder();
        out[0] = MockRestServiceServer.bindTo(builder).build();
        return new ManagementClient("test-key", builder, props);
    }

    private static WorkerRegistrationProperties props(String id, String mgmt) {
        WorkerRegistrationProperties p = new WorkerRegistrationProperties();
        p.setId(id);
        p.setDisplayName("Inkom");
        p.setBaseUrl("http://worker-1:8080");
        p.setManagementBaseUrl(mgmt);
        return p;
    }

    @Test
    void register_postsToManagementWithApiKeyAndBody() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/register"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andExpect(jsonPath("$.id").value("printer-1"))
            .andExpect(jsonPath("$.displayName").value("Inkom"))
            .andExpect(jsonPath("$.baseUrl").value("http://worker-1:8080"))
            .andRespond(withSuccess());

        assertThatCode(client::register).doesNotThrowAnyException();
        server[0].verify();
    }

    @Test
    void deregister_postsToDeregisterEndpoint() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/printer-1/deregister"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andRespond(withSuccess());

        assertThatCode(client::deregister).doesNotThrowAnyException();
        server[0].verify();
    }

    @Test
    void register_blankManagementUrl_skipsWithoutCalling() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "   "), server);
        assertThatCode(client::register).doesNotThrowAnyException();
        server[0].verify();
    }

    @Test
    void register_swallowsTransportErrors() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        ManagementClient client = client(props("printer-1", "http://management:8080"), server);
        server[0].expect(requestTo("http://management:8080/api/internal/printers/register"))
            .andRespond(request -> { throw new org.springframework.web.client.ResourceAccessException("down"); });
        assertThatCode(client::register).doesNotThrowAnyException();
    }
}
