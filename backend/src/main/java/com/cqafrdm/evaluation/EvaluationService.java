package com.cqafrdm.evaluation;

import com.cqafrdm.appeal.Appeal;
import com.cqafrdm.appeal.AppealDto;
import com.cqafrdm.appeal.AppealRepository;
import com.cqafrdm.checklist.ChecklistService;
import com.cqafrdm.checklist.ChecklistVersion;
import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.common.ValidationException;
import com.cqafrdm.conversation.Conversation;
import com.cqafrdm.conversation.ConversationRepository;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.ai.EvaluationOutcome;
import com.cqafrdm.evaluation.ai.EvaluationRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final TypeReference<List<TranscriptMessage>> TRANSCRIPT = new TypeReference<>() {};

    private final EvaluationRepository evaluations;
    private final AppealRepository appeals;
    private final ConversationRepository conversations;
    private final ChecklistService checklists;
    private final EvaluationRunner runner;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public EvaluationService(EvaluationRepository evaluations, AppealRepository appeals,
                             ConversationRepository conversations, ChecklistService checklists,
                             EvaluationRunner runner, ObjectMapper mapper) {
        this.evaluations = evaluations;
        this.appeals = appeals;
        this.conversations = conversations;
        this.checklists = checklists;
        this.runner = runner;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** Без @Transactional: вызов LLM длится 10–35 с, соединение с БД держать нельзя. */
    public EvaluationDto evaluate(String conversationId, Long checklistVersionId) {
        Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("Диалог не найден: " + conversationId));
        ChecklistVersion version = checklists.getEntity(checklistVersionId);

        List<TranscriptMessage> transcript;
        try {
            transcript = mapper.convertValue(conv.getTranscript(), TRANSCRIPT);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Транскрипт диалога " + conversationId + " повреждён");
        }

        EvaluationOutcome o = runner.evaluate(conv.getId(), conv.getChannel(), conv.getLanguage(),
                transcript, version.getCriteria());

        Evaluation saved = evaluations.save(new Evaluation(conv.getId(), version.getId(), o.status().name(),
                o.result(), o.model(), o.inputTokens(), o.outputTokens(),
                BigDecimal.valueOf(o.costUsd()), o.error()));
        return toDto(saved);
    }

    public int evaluateGroup(String groupId, Long checklistVersionId) {
        checklists.getEntity(checklistVersionId); // 404 сразу, а не в фоне
        List<String> all = conversations.findByGroupId(groupId).stream().map(Conversation::getId).toList();
        if (all.isEmpty()) {
            throw new NotFoundException("В группе нет диалогов: " + groupId);
        }
        // уже успешно оценённые этой версией чек-листа пропускаем (экономим бесплатный лимит модели)
        List<String> ids = all.stream()
                .filter(id -> !evaluations.existsByConversationIdAndChecklistVersionIdAndStatus(id, checklistVersionId, "OK"))
                .toList();
        log.info("batch {}: всего {}, к оценке {}, уже оценено {}", groupId, all.size(), ids.size(), all.size() - ids.size());
        ids.forEach(id -> pool.submit(() -> {
            try {
                evaluate(id, checklistVersionId);
            } catch (Exception e) {
                log.warn("batch {}: {}", id, e.getMessage());
            }
        }));
        return ids.size();
    }

    @Transactional(readOnly = true)
    public EvaluationDto get(Long id) {
        Evaluation e = evaluations.findById(id)
                .orElseThrow(() -> new NotFoundException("Оценка не найдена: " + id));
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDto> listByConversation(String conversationId) {
        return evaluations.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId)
                .stream().map(this::toDto).toList();
    }

    private EvaluationDto toDto(Evaluation e) {
        List<Appeal> list = appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId());
        EvaluationResult finalResult = list.stream()
                .filter(a -> "DECIDED".equals(a.getStatus()) && a.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());
        List<AppealDto> appealDtos = list.stream()
                .map(a -> AppealDto.from(a, e.getConversationId())).toList();
        return new EvaluationDto(e.getId(), e.getConversationId(), e.getChecklistVersionId(),
                e.getStatus(), e.getError(), e.getResult(), appealDtos, finalResult,
                e.getModel(), e.getCostUsd(), e.getCreatedAt());
    }
}