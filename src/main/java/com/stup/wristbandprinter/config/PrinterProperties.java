package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "printer")
public class PrinterProperties {

    private String host = "localhost";
    private int port = 9100;
    private int timeoutMs = 5000;
    private int maxRetries = 2;
    private int retryBackoffMs = 500;

    // Defensive cache clear: prepended to every job so any objects accumulated in the printer's
    // RAM drive (R:) are wiped before each label. Images are sent as inline ^GFA and never stored,
    // so this is a no-op in normal operation — it only guards against build-up that historically
    // caused the printer to stall after a number of prints. R: only, so no flash wear.
    private boolean clearCacheEnabled = true;
    private String clearCommand = "^XA^IDR:*.*^FS^XZ";

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    public boolean isClearCacheEnabled() { return clearCacheEnabled; }
    public void setClearCacheEnabled(boolean clearCacheEnabled) { this.clearCacheEnabled = clearCacheEnabled; }

    public String getClearCommand() { return clearCommand; }
    public void setClearCommand(String clearCommand) { this.clearCommand = clearCommand; }
}
