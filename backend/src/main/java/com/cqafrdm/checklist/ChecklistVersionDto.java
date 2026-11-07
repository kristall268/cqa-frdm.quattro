package com.cqafrdm.checklist;

import java.time.OffsetDateTime;
import java.util.List;

public record ChecklistVersionDto(Long id, String name, int version,
                                  List<Criterion> criteria, OffsetDateTime createdAt) {

    public static ChecklistVersionDto from(ChecklistVersion v) {
        return new ChecklistVersionDto(v.getId(), v.getName(), v.getVersion(),
                v.getCriteria(), v.getCreatedAt());
    }
}