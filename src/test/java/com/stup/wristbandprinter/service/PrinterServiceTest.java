package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrinterProperties;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterServiceTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void send_writesZplToSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(client.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            PrinterService service = new PrinterService(propsFor("localhost", port), registry);
            service.send("^XA^XZ");

            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("^XA^XZ");
            assertThat(registry.get("wristband.printer.send").timer().count()).isEqualTo(1);
        }
    }

    @Test
    void send_throwsPrinterUnavailableException_whenHostUnreachable() {
        PrinterService service = new PrinterService(propsFor("localhost", 19999), registry);

        assertThatThrownBy(() -> service.send("^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class)
            .hasMessageContaining("localhost");
    }

    @Test
    void send_succeedsAfterTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        PrinterProperties props = propsFor("localhost", 9100);
        props.setMaxRetries(2);
        PrinterService service = new PrinterService(props, registry) {
            @Override
            protected void doSend(String zpl) throws IOException {
                if (attempts.incrementAndGet() < 3) {
                    throw new IOException("transient");
                }
            }
        };

        assertThatCode(() -> service.send("^XA^XZ")).doesNotThrowAnyException();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void send_throwsAfterRetriesExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        PrinterProperties props = propsFor("localhost", 9100);
        props.setMaxRetries(2);
        PrinterService service = new PrinterService(props, registry) {
            @Override
            protected void doSend(String zpl) throws IOException {
                attempts.incrementAndGet();
                throw new IOException("always down");
            }
        };

        assertThatThrownBy(() -> service.send("^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class);
        assertThat(attempts.get()).isEqualTo(3);
    }

    private PrinterProperties propsFor(String host, int port) {
        PrinterProperties props = new PrinterProperties();
        props.setHost(host);
        props.setPort(port);
        props.setTimeoutMs(1000);
        props.setMaxRetries(2);
        props.setRetryBackoffMs(1);
        return props;
    }
}
