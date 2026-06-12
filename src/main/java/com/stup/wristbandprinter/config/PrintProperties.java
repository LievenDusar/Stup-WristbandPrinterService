package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "print")
public class PrintProperties {

    /** Hard cap on copies per job; a request above this is rejected with 400. */
    private int maxCopies = 200;

    public int getMaxCopies() { return maxCopies; }
    public void setMaxCopies(int maxCopies) { this.maxCopies = maxCopies; }
}
