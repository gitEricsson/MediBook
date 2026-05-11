package com.medibook.domain.schedule.repository;

import com.medibook.domain.schedule.entity.NoteTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteTemplateRepository extends JpaRepository<NoteTemplate, Long> {

    @Query("""
        SELECT t FROM NoteTemplate t
        WHERE t.isActive = true
          AND (t.doctor IS NULL OR t.doctor.id = :doctorId)
        ORDER BY CASE WHEN t.doctor IS NULL THEN 1 ELSE 0 END ASC, t.name ASC
        """)
    List<NoteTemplate> findAvailableForDoctor(Long doctorId);

    List<NoteTemplate> findByTemplateTypeAndIsActive(String templateType, boolean isActive);
}
