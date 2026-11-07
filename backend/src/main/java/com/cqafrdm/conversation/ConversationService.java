package com.cqafrdm.conversation;

import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.evaluation.EvaluationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private static final int PREVIEW_LENGTH = 120;

    private final ConversationRepository conversations;
    private final EvaluationRepository evaluations;

    public ConversationService(ConversationRepository conversations, EvaluationRepository evaluations) {
        this.conversations = conversations;
        this.evaluations = evaluations;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> list(String groupId, String language, String channel) {
        return conversations.findAll().stream()
                .filter(c -> isBlank(groupId) || c.getGroupId().equals(groupId))
                .filter(c -> isBlank(language) || c.getLanguage().equalsIgnoreCase(language))
                .filter(c -> isBlank(channel) || c.getChannel().equalsIgnoreCase(channel))
                .sorted(Comparator.comparing(Conversation::getId))
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDto get(String id) {
        return conversations.findById(id).map(ConversationDto::from)
                .orElseThrow(() -> new NotFoundException("Диалог не найден: " + id));
    }

    private ConversationSummaryDto toSummary(Conversation c) {
        JsonNode t = c.getTranscript();
        int count = t == null ? 0 : t.size();
        String preview = "";
        if (count > 0) {
            String text = t.get(0).path("text").asText("");
            preview = text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "…" : text;
        }
        var last = evaluations.findFirstByConversationIdOrderByCreatedAtDescIdDesc(c.getId())
                .map(e -> new ConversationSummaryDto.LastEvaluation(
                        e.getId(), e.getTotalScore(), e.getGrossError(), e.getStatus()))
                .orElse(null);
        return new ConversationSummaryDto(c.getId(), c.getGroupId(), c.getChannel(),
                c.getLanguage(), preview, count, last);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}