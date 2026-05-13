package com.medibook.common.sequence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class SequenceService {

    private final SequenceRepository repository;

    @Transactional
    public long getNextValue(String name) {
        Sequence seq = repository.findByNameForUpdate(name)
                .orElseGet(() -> repository.save(Sequence.builder()
                        .name(name)
                        .currentValue(0L)
                        .lastDate(LocalDate.now())
                        .build()));

        LocalDate today = LocalDate.now();
        if (seq.getLastDate() != null && !seq.getLastDate().equals(today)) {
            seq.setCurrentValue(0L);
            seq.setLastDate(today);
        }

        seq.setCurrentValue(seq.getCurrentValue() + 1);
        repository.save(seq);
        return seq.getCurrentValue();
    }
}
