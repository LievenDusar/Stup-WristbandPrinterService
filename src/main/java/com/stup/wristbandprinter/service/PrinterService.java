package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrinterProperties;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Service
public class PrinterService {

    private static final Logger log = LoggerFactory.getLogger(PrinterService.class);

    private final PrinterProperties props;

    public PrinterService(PrinterProperties props) {
        this.props = props;
    }

    public void send(String zpl) {
        int maxAttempts = props.getMaxRetries() + 1;
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("Sending ZPL to {}:{} (attempt {}/{})",
                props.getHost(), props.getPort(), attempt, maxAttempts);
            try {
                doSend(zpl);
                log.info("ZPL sent successfully ({} bytes)", zpl.length());
                return;
            } catch (IOException e) {
                lastFailure = e;
                log.warn("Print attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    backoff();
                }
            }
        }

        throw new PrinterUnavailableException(
            "Could not connect to printer at " + props.getHost() + ":" + props.getPort()
                + " after " + maxAttempts + " attempt(s) — " + lastFailure.getMessage(),
            lastFailure);
    }

    protected void doSend(String zpl) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(props.getHost(), props.getPort()),
                props.getTimeoutMs()
            );
            socket.setSoTimeout(props.getTimeoutMs());
            OutputStream out = socket.getOutputStream();
            out.write(zpl.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void backoff() {
        try {
            Thread.sleep(props.getRetryBackoffMs());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PrinterUnavailableException("Interrupted while retrying print", ie);
        }
    }
}
