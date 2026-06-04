package com.stup.wristbandprinter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.domain.PrintJobResponse;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WristbandIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String API_KEY = "itest-key";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpServer workerServer;
    private static final BlockingQueue<String> printerReceived = new LinkedBlockingQueue<>();

    static {
        try {
            workerServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        workerServer.createContext("/api/internal/print", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                PrintForwardRequest req = MAPPER.readValue(body, PrintForwardRequest.class);
                printerReceived.add(req.zpl());
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        workerServer.start();
    }

    @AfterAll
    static void stopFakeWorker() {
        workerServer.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("security.api-key", () -> API_KEY);
        registry.add("cluster.printers[0].id", () -> "printer-1");
        registry.add("cluster.printers[0].display-name", () -> "Integration printer");
        registry.add("cluster.printers[0].base-url",
            () -> "http://localhost:" + workerServer.getAddress().getPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void printJob_reachesDone_andWorkerReceivesZpl() {
        ResponseEntity<PrintJobResponse> response = rest.exchange(
            url("/api/wristbands/print"), org.springframework.http.HttpMethod.POST,
            jsonRequest(sampleBody()), PrintJobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().printerId()).isEqualTo("printer-1");
        assertThat(response.getBody().printerName()).isEqualTo("Integration printer");
        String jobId = response.getBody().jobId().toString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jobStatus(jobId)).isEqualTo(PrintJobStatus.DONE));

        String zpl = printerReceived.poll();
        assertThat(zpl).isNotNull();
        assertThat(zpl).startsWith("^XA").contains("^XZ");
    }

    @Test
    void concurrentSubmissions_areAllQueuedAndProcessed() throws Exception {
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<CompletableFuture<HttpStatus>> futures = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> rest.exchange(
                    url("/api/wristbands/print"), org.springframework.http.HttpMethod.POST,
                    jsonRequest(sampleBody()), PrintJobResponse.class).getStatusCode(), pool)
                    .thenApply(s -> (HttpStatus) s))
                .toList();

            for (CompletableFuture<HttpStatus> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.ACCEPTED);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<PrintJobResponse> jobs = listJobs();
            assertThat(jobs).hasSizeGreaterThanOrEqualTo(n);
            assertThat(jobs).allMatch(j -> j.status() == PrintJobStatus.DONE);
        });
    }

    @Test
    void sseStream_emitsJobUpdates() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        client.sendAsync(
                HttpRequest.newBuilder(URI.create(url("/api/wristbands/jobs/stream")))
                    .header("X-API-Key", API_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofLines())
            .thenAccept(resp -> resp.body().forEach(lines::add));

        // Give the subscription time to register before triggering an update.
        Thread.sleep(500);
        rest.exchange(url("/api/wristbands/print"), org.springframework.http.HttpMethod.POST,
            jsonRequest(sampleBody()), PrintJobResponse.class);

        String dataLine = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith("data:")) {
                dataLine = line;
                break;
            }
        }
        assertThat(dataLine).as("expected at least one SSE data line").isNotNull();
    }

    private PrintJobStatus jobStatus(String jobId) {
        PrintJobResponse body = rest.exchange(url("/api/wristbands/jobs/" + jobId),
            org.springframework.http.HttpMethod.GET, authOnly(), PrintJobResponse.class).getBody();
        return body == null ? null : body.status();
    }

    private List<PrintJobResponse> listJobs() {
        ResponseEntity<PrintJobResponse[]> resp = rest.exchange(url("/api/wristbands/jobs"),
            org.springframework.http.HttpMethod.GET, authOnly(), PrintJobResponse[].class);
        return List.of(resp.getBody());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> jsonRequest(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", API_KEY);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        return new HttpEntity<>(headers);
    }

    private String sampleBody() {
        return """
            {
              "eventName": "Pukkelpop 2026",
              "firstName": "Jan",
              "lastName": "Janssens",
              "associationName": "STUP vzw",
              "barcodeValue": "123456789"
            }
            """;
    }
}
