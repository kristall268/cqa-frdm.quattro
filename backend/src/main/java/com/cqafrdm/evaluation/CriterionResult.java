package com.cqafrdm.evaluation;

import com.cqafrdm.checklist.CriterionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * needsReview  — вторая (проверяющая) модель не согласилась с оценкой: нужен взгляд человека (null — проверки не было).
 * verification — короткий комментарий проверяющей модели или проверяющего человека.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CriterionResult(String criterionId,
                              String title,
                              CriterionType type,
                              BigDecimal weight,
                              String answer,
                              @JsonProperty("isNa") boolean isNa,
                              BigDecimal points,
                              String reason,
                              List<Integer> evidenceIds,
                              CheckedRange checkedRange,
                              Boolean needsReview,
                              String verification) {

    /** Без данных проверки (совместимость со старым кодом). */
    public CriterionResult(String criterionId, String title, CriterionType type, BigDecimal weight, String answer,
                           boolean isNa, BigDecimal points, String reason, List<Integer> evidenceIds,
                           CheckedRange checkedRange) {
        this(criterionId, title, type, weight, answer, isNa, points, reason, evidenceIds, checkedRange, null, null);
    }

    public CriterionResult withVerification(Boolean needsReview, String verification) {
        return new CriterionResult(criterionId, title, type, weight, answer, isNa, points, reason, evidenceIds,
                checkedRange, needsReview, verification);
    }
}
