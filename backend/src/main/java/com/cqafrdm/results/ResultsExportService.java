package com.cqafrdm.results;

import com.cqafrdm.appeal.Appeal;
import com.cqafrdm.appeal.AppealRepository;
import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.conversation.Conversation;
import com.cqafrdm.conversation.ConversationRepository;
import com.cqafrdm.evaluation.Evaluation;
import com.cqafrdm.evaluation.EvaluationRepository;
import com.cqafrdm.evaluation.EvaluationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Собирает results.json для results/dev или results/prod: одна запись на каждый диалог группы,
 * без пропусков и повторов; неоценённые и упавшие диалоги — status=error (не скрываются и не обнуляются).
 */
@Service
public class ResultsExportService {

    private final ConversationRepository conversations;
    private final EvaluationRepository evaluations;
    private final AppealRepository appeals;
    private final String teamName;
    private final String modelMode;
    private final String llmBaseUrl;
    private final String llmModel;
    private final String localUrl;

    public ResultsExportService(ConversationRepository conversations, EvaluationRepository evaluations,
                                AppealRepository appeals,
                                @Value("${TEAM_NAME:quattro}") String teamName,
                                @Value("${RESULTS_MODEL_MODE:}") String modelMode,
                                @Value("${LLM_BASE_URL:}") String llmBaseUrl,
                                @Value("${LLM_MODEL:}") String llmModel,
                                @Value("${LLM_LOCAL_BASE_URL:}") String localUrl) {
        this.localUrl = localUrl;
        this.conversations = conversations;
        this.evaluations = evaluations;
        this.appeals = appeals;
        this.teamName = teamName;
        this.modelMode = modelMode;
        this.llmBaseUrl = llmBaseUrl;
        this.llmModel = llmModel;
    }

    /**
     * @param checklistVersionId null — последняя оценка любой версией
     * @param useFinal           true — с учётом решений по апелляциям, false — исходная оценка AI
     */
    @Transactional(readOnly = true)
    public Map<String, Object> export(String groupId, Long checklistVersionId, boolean useFinal) {
        List<Conversation> list = new ArrayList<>(conversations.findByGroupId(groupId));
        if (list.isEmpty()) throw new NotFoundException("В группе нет диалогов: " + groupId);
        list.sort(Comparator.comparing(Conversation::getId));

        Set<String> models = new LinkedHashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Conversation c : list) {
            List<Evaluation> evs = evaluations.findByConversationIdOrderByCreatedAtDescIdDesc(c.getId()).stream()
                    .filter(e -> checklistVersionId == null || checklistVersionId.equals(e.getChecklistVersionId()))
                    .toList();
            Optional<Evaluation> ok = evs.stream().filter(e -> "OK".equals(e.getStatus()) && e.getResult() != null).findFirst();
            if (ok.isPresent()) {
                Evaluation e = ok.get();
                if (e.getModel() != null && !e.getModel().isBlank())
                    for (String m : e.getModel().replace(" + проверка ", ", ").split(","))
                        if (!m.isBlank()) models.add(m.trim());
                results.add(ResultsMapper.toEntry(c.getId(), useFinal ? finalResult(e) : e.getResult()));
            } else if (!evs.isEmpty()) {
                results.add(ResultsMapper.error(c.getId(), evs.get(0).getError()));
            } else {
                results.add(ResultsMapper.error(c.getId(), "Диалог не оценён"));
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("mode", mode());
        model.put("name", models.isEmpty() ? (llmModel == null || llmModel.isBlank() ? "mock" : llmModel) : String.join(", ", models));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "1.0");
        root.put("team_name", teamName);
        root.put("model", model);
        root.put("results", results);
        return root;
    }

    private EvaluationResult finalResult(Evaluation e) {
        return appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId()).stream()
                .filter(a -> "DECIDED".equals(a.getStatus()) && a.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());
    }

    /** local | external_api | hybrid: явное значение из .env, иначе по адресу модели. */
    String mode() {
        if (modelMode != null && !modelMode.isBlank()) return modelMode.trim();
        String u = llmBaseUrl == null ? "" : llmBaseUrl.toLowerCase(Locale.ROOT);
        boolean primaryLocal = u.isBlank() || u.contains("localhost") || u.contains("127.0.0.1") || u.contains("ollama")
                || u.contains(":11434") || u.contains("host.docker.internal");
        boolean hasLocal = localUrl != null && !localUrl.isBlank();
        if (!primaryLocal && hasLocal) return "hybrid";    // чаты — облако, звонки — локальная модель
        if (primaryLocal) return "local";
        return "external_api";
    }
}
