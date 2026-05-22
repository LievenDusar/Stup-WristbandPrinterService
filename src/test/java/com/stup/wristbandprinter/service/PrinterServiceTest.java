package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrinterProperties;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterServiceTest {

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

            PrinterService service = new PrinterService(propsFor("localhost", port));
            service.send("^XA^XZ");

            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("^XA^XZ");
        }
    }

    @Test
    void send_throwsPrinterUnavailableException_whenHostUnreachable() {
        PrinterService service = new PrinterService(propsFor("localhost", 19999));

        assertThatThrownBy(() -> service.send("^XA^XZ"))
            .isInstanceOf(PrinterUnavailableException.class)
            .hasMessageContaining("localhost");
    }

    private PrinterProperties propsFor(String host, int port) {
        PrinterProperties props = new PrinterProperties();
        props.setHost(host);
        props.setPort(port);
        props.setTimeoutMs(1000);
        return props;
    }
}
