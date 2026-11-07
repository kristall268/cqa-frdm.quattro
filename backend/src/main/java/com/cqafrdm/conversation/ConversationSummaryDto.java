package com.cqafrdm.conversation;

import java.math.BigDecimal;

public record ConversationSummaryDto(String id, String groupId, String channel, String language,
                                     String preview, int messageCount, LastEvaluation lastEvaluation) {

    public record LastEvaluation(Long id, BigDecimal totalScore, Boolean grossError, String status) {}
}