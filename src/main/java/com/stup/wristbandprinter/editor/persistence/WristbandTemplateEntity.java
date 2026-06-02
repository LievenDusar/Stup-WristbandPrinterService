package com.stup.wristbandprinter.editor.persistence;

import com.stup.wristbandprinter.editor.domain.TemplateDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wristband_template")
public class WristbandTemplateEntity {

    @Id
    private UUID id;

    private String slug;
    private String name;
    private String projectType;
    private String defaultPreviewColor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private TemplateDefinition definition;

    @Column(columnDefinition = "text")
    private String generatedZpl;

    private Instant createdAt;
    private Instant updatedAt;
    private boolean deleted;

    protected WristbandTemplateEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }

    public String getDefaultPreviewColor() { return defaultPreviewColor; }
    public void setDefaultPreviewColor(String defaultPreviewColor) { this.defaultPreviewColor = defaultPreviewColor; }

    public TemplateDefinition getDefinition() { return definition; }
    public void setDefinition(TemplateDefinition definition) { this.definition = definition; }

    public String getGeneratedZpl() { return generatedZpl; }
    public void setGeneratedZpl(String generatedZpl) { this.generatedZpl = generatedZpl; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
