package com.stup.wristbandprinter.editor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_asset")
public class TemplateAssetEntity {

    @Id
    private UUID id;

    private String name;

    @Column(columnDefinition = "bytea")
    private byte[] png;

    private int width;
    private int height;
    private Instant createdAt;

    public TemplateAssetEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public byte[] getPng() { return png; }
    public void setPng(byte[] png) { this.png = png; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
