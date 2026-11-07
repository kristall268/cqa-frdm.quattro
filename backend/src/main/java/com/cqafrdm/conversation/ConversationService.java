package com.cqafrdm.conversation;

import com.cqafrdm.appeal.Appeal;
import com.cqafrdm.appeal.AppealRepository;
import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.Evaluation;
import com.cqafrdm.evaluation.EvaluationResult;

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
    private final AppealRepository appeals;

    public ConversationService(ConversationRepository conversations, EvaluationRepository evaluations,
                               AppealRepository appeals) {
        this.conversations = conversations;
        this.evaluations = evaluations;
        this.appeals = appeals;
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
            // превью — первая реплика клиента («Чат начат» от system неинформативно)
            JsonNode first = t.get(0);
            for (JsonNode m : t) {
                if ("client".equalsIgnoreCase(m.path("speaker").asText())) { first = m; break; }
            }
            String text = first.path("text").asText("");
            preview = text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "…" : text;
        }
        var last = evaluations.findFirstByConversationIdOrderByCreatedAtDescIdDesc(c.getId())
                .map(this::lastEvaluation)
                .orElse(null);
        return new ConversationSummaryDto(c.getId(), c.getGroupId(), c.getChannel(),
                c.getLanguage(), preview, count, last);
    }

    private ConversationSummaryDto.LastEvaluation lastEvaluation(Evaluation e) {
        EvaluationResult fin = appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId()).stream()
                .filter(a -> "DECIDED".equals(a.getStatus()) && a.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());
        if (fin == null) {
            return new ConversationSummaryDto.LastEvaluation(e.getId(), null, null, e.getStatus(), null, null);
        }
        int review = (int) fin.criteria().stream().filter(r -> Boolean.TRUE.equals(r.needsReview())).count();
        String gross = fin.criteria().stream()
                .filter(r -> r.type() == CriterionType.FLAG && !r.isNa() && "YES".equals(r.answer()))
                .map(CriterionResult::reason).findFirst().orElse(null);
        return new ConversationSummaryDto.LastEvaluation(e.getId(), fin.totalScore(), fin.grossError(), e.getStatus(),
                review, gross);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}