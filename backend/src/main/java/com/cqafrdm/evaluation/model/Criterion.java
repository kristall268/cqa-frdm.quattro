package com.cqafrdm.evaluation.model;

import java.util.List;

/** Критерий чек-листа (CONTRACT.md §4). Хранится в checklist_version.criteria (jsonb). */
public record Criterion(
        String id,
        String title,
        String section,
        String rule,
        CriterionType type,
        double weight,
        boolean naAllowed,
        String naCondition,
        List<ScaleStep> scale) {

    public int maxScale() {
        return scale == null ? 0 : scale.stream().mapToInt(ScaleStep::value).max().orElse(0);
    }

    public boolean hasScaleValue(int v) {
        return scale != null && scale.stream().anyMatch(s -> s.value() == v);
    }
}
