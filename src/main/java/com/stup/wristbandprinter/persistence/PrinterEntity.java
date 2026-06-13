package com.stup.wristbandprinter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "printers")
public class PrinterEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private boolean online;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant registeredAt;

    protected PrinterEntity() {
    }

    public PrinterEntity(String id, String displayName, String baseUrl) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.registeredAt = Instant.now();
    }

    public String getId()              { return id; }
    public String getDisplayName()     { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getBaseUrl()         { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isOnline()          { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public boolean isHidden()          { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isDefault()         { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public Instant getLastSeenAt()     { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getRegisteredAt()   { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
}
