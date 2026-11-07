package com.cqafrdm.appeal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AppealDto(Long id, Long evaluationId, String conversationId, String criterionId,
                        String comment, List<Integer> evidenceIds, String status,
                        String decision, String newAnswer, Boolean newIsNa,
                        String reviewerReason, BigDecimal finalTotal,
                        OffsetDateTime createdAt, OffsetDateTime decidedAt) {

    public static AppealDto from(Appeal a, String conversationId) {
        return new AppealDto(a.getId(), a.getEvaluationId(), conversationId, a.getCriterionId(),
                a.getComment(), a.getEvidenceIds(), a.getStatus(), a.getDecision(),
                a.getNewAnswer(), a.getNewIsNa(), a.getReviewerReason(), a.getFinalTotal(),
                a.getCreatedAt(), a.getDecidedAt());
    }
}