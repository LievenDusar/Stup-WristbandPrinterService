package com.stup.wristbandprinter.editor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TemplateAssetRepository extends JpaRepository<TemplateAssetEntity, UUID> {
}
