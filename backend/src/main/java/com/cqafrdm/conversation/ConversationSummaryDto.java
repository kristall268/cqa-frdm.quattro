package com.cqafrdm.conversation;

import java.math.BigDecimal;

public record ConversationSummaryDto(String id, String groupId, String channel, String language,
                                     String preview, int messageCount, LastEvaluation lastEvaluation) {

    /**
     * totalScore/grossError — с учётом решений по апелляциям (final);
     * needsReviewCount — сколько критериев вторая модель пометила «нужна проверка»;
     * grossReason — обоснование критерия грубых ошибок, если нарушение выявлено.
     */
    public record LastEvaluation(Long id, BigDecimal totalScore, Boolean grossError, String status,
                                 Integer needsReviewCount, String grossReason) {}
}