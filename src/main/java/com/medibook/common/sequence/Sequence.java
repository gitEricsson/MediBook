package com.medibook.common.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "sequences")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Sequence {

    @Id
    @Column(length = 50)
    private String name;

    @Column(name = "current_value", nullable = false)
    private long currentValue;

    @Column(name = "last_date")
    private LocalDate lastDate;
}
