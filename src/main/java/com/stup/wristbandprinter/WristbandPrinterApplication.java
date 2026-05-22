package com.stup.wristbandprinter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WristbandPrinterApplication {
    public static void main(String[] args) {
        SpringApplication.run(WristbandPrinterApplication.class, args);
    }
}
