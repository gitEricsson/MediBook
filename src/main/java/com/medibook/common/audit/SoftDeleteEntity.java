package com.medibook.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@MappedSuperclass
public abstract class SoftDeleteEntity extends AuditableEntity {

    @Column(name = "deleted_at", nullable = true)
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", nullable = true)
    private Long deletedBy;

    /**
     * Check if this entity is logically deleted.
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Perform a soft delete, recording who deleted it and when.
     */
    public void softDelete(Long deletedBy) {
        this.deletedAt = LocalDateTime.now();
        this.deletedBy = deletedBy;
    }

    /**
     * Restore a soft-deleted entity.
     */
    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }
}
