package com.medibook.domain.schedule.repository;

import com.medibook.domain.schedule.entity.DoctorSlotBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DoctorSlotBlockRepository extends JpaRepository<DoctorSlotBlock, Long> {

    List<DoctorSlotBlock> findByDoctorIdAndBlockDateBetweenOrderByBlockDateAscStartTimeAsc(
            Long doctorId, LocalDate from, LocalDate to);

    /** All blocks across the hospital in the date window — used by the admin audit view. */
    @Query("""
        SELECT b FROM DoctorSlotBlock b
        WHERE b.blockDate BETWEEN :from AND :to
        ORDER BY b.blockDate DESC, b.startTime ASC
        """)
    List<DoctorSlotBlock> findAllInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
