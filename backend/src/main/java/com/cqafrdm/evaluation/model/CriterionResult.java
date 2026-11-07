package com.cqafrdm.evaluation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Результат по одному критерию (CONTRACT.md §4).
 * answer: BINARY/FLAG -> "YES"|"NO", SCALE -> значение ступени ("15"); null если isNa.
 * points: считает только ScoringEngine; null для N/A и FLAG.
 */
public record CriterionResult(
        String criterionId,
        String title,
        CriterionType type,
        double weight,
        String answer,
        @JsonProperty("isNa") boolean isNa,
        Double points,
        String reason,
        List<Integer> evidenceIds,
        CheckedRange checkedRange) {

    public CriterionResult withPoints(Double p) {
        return new CriterionResult(criterionId, title, type, weight, answer, isNa, p, reason, evidenceIds, checkedRange);
    }

    public CriterionResult withAnswer(String newAnswer, boolean newIsNa, String newReason) {
        return new CriterionResult(criterionId, title, type, weight, newIsNa ? null : newAnswer, newIsNa, null,
                newReason, evidenceIds, checkedRange);
    }
}
