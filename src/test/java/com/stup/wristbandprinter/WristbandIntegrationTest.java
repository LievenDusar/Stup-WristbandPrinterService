package com.stup.wristbandprinter;

import com.stup.wristbandprinter.domain.PrintJobResponse;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
class WristbandIntegrationTest {

    private static final String API_KEY = "itest-key";

    private static final ServerSocket printerSocket;
    private static final BlockingQueue<String> printerReceived = new LinkedBlockingQueue<>();
    private static volatile boolean printerRunning = true;

    static {
        try {
            printerSocket = new ServerSocket(0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        Thread t = new Thread(WristbandIntegrationTest::acceptLoop, "fake-printer");
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop() {
        while (printerRunning) {
            try (Socket s = printerSocket.accept()) {
                printerReceived.add(new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // socket closed during shutdown
            }
        }
    }

    @AfterAll
    static void stopFakePrinter() throws IOException {
        printerRunning = false;
        printerSocket.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("printer.host", () -> "localhost");
        registry.add("printer.port", printerSocket::getLocalPort);
        registry.add("printer.max-retries", () -> 0);
        registry.add("security.api-key", () -> API_KEY);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:itest;DB_CLOSE_DELAY=-1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void printJob_reachesDone_andPrinterReceivesZpl() {
        ResponseEntity<PrintJobResponse> response = rest.exchange(
            url("/api/wristbands/print"), org.springframework.http.HttpMethod.POST,
            jsonRequest(sampleBody()), PrintJobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
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
                HttpRequest.newBuilder(URI.create(url("/api/wristbands/jobs/stream"))).GET().build(),
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
