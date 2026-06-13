package com.stup.wristbandprinter.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/** This worker's self-registration identity + where to reach management. Worker-only. */
@ConfigurationProperties(prefix = "worker")
@Profile("worker")
public class WorkerRegistrationProperties {

    private String id;
    private String displayName;
    private String baseUrl;
    private String managementBaseUrl;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getManagementBaseUrl() { return managementBaseUrl; }
    public void setManagementBaseUrl(String managementBaseUrl) { this.managementBaseUrl = managementBaseUrl; }
}
