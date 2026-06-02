package com.stup.wristbandprinter.editor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WristbandTemplateRepository extends JpaRepository<WristbandTemplateEntity, UUID> {

    List<WristbandTemplateEntity> findByDeletedFalseOrderByUpdatedAtDesc();

    List<WristbandTemplateEntity> findByProjectTypeAndDeletedFalseOrderByUpdatedAtDesc(String projectType);

    Optional<WristbandTemplateEntity> findByIdAndDeletedFalse(UUID id);

    Optional<WristbandTemplateEntity> findBySlugAndDeletedFalse(String slug);

    boolean existsBySlug(String slug);
}
