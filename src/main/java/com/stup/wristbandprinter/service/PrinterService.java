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
        log.info("Sending ZPL to {}:{}", props.getHost(), props.getPort());
        try (Socket socket = new Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(props.getHost(), props.getPort()),
                props.getTimeoutMs()
            );
            socket.setSoTimeout(props.getTimeoutMs());
            OutputStream out = socket.getOutputStream();
            out.write(zpl.getBytes(StandardCharsets.UTF_8));
            out.flush();
            log.info("ZPL sent successfully ({} bytes)", zpl.length());
        } catch (IOException e) {
            throw new PrinterUnavailableException(
                "Could not connect to printer at " + props.getHost() + ":" + props.getPort()
                    + " — " + e.getMessage(), e);
        }
    }
}
