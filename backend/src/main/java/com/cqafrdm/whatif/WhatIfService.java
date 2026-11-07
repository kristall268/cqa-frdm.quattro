package com.cqafrdm.whatif;

import com.cqafrdm.appeal.Appeal;
import com.cqafrdm.appeal.AppealRepository;
import com.cqafrdm.checklist.ChecklistService;
import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.common.ValidationException;
import com.cqafrdm.conversation.Conversation;
import com.cqafrdm.conversation.ConversationRepository;
import com.cqafrdm.evaluation.Evaluation;
import com.cqafrdm.evaluation.EvaluationRepository;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Пересчёт уже оценённых диалогов по другой конфигурации чек-листа — мгновенно и без затрат на модель. */
@Service
public class WhatIfService {

    private final ConversationRepository conversations;
    private final EvaluationRepository evaluations;
    private final AppealRepository appeals;
    private final ChecklistService checklists;
    private final ScoringEngine engine;

    public WhatIfService(ConversationRepository conversations, EvaluationRepository evaluations,
                         AppealRepository appeals, ChecklistService checklists, ScoringEngine engine) {
        this.conversations = conversations;
        this.evaluations = evaluations;
        this.appeals = appeals;
        this.checklists = checklists;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> simulate(WhatIfRequest req) {
        List<Criterion> criteria = req.criteria() != null && !req.criteria().isEmpty() ? req.criteria()
                : req.checklistVersionId() != null ? checklists.getEntity(req.checklistVersionId()).getCriteria() : null;
        if (criteria == null) throw new ValidationException("Передайте criteria или checklistVersionId");
        List<String> errors = engine.validateChecklist(criteria);
        if (!errors.isEmpty()) throw new ValidationException(String.join("; ", errors));

        List<Conversation> list = req.groupId() == null || req.groupId().isBlank()
                ? conversations.findAll() : conversations.findByGroupId(req.groupId());
        List<WhatIfCalculator.Item> items = new ArrayList<>();
        for (Conversation c : list) {
            Optional<Evaluation> ok = evaluations.findByConversationIdOrderByCreatedAtDescIdDesc(c.getId()).stream()
                    .filter(e -> "OK".equals(e.getStatus()) && e.getResult() != null).findFirst();
            ok.ifPresent(e -> items.add(WhatIfCalculator.compute(c.getId(), finalResult(e), criteria, engine)));
        }
        items.sort(Comparator.comparing((WhatIfCalculator.Item i) -> i.delta() == null ? BigDecimal.ZERO : i.delta().abs()).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluated", items.size());
        out.put("changed", items.stream().filter(i -> i.delta() != null && i.delta().signum() != 0).count());
        out.put("partial", items.stream().filter(i -> "PARTIAL".equals(i.status())).count());
        out.put("avgBefore", avg(items.stream().map(WhatIfCalculator.Item::before).toList()));
        out.put("avgAfter", avg(items.stream().map(WhatIfCalculator.Item::after).toList()));
        out.put("items", items);
        return out;
    }

    private EvaluationResult finalResult(Evaluation e) {
        return appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId()).stream()
                .filter(a -> "DECIDED".equals(a.getStatus()) && a.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());
    }

    private static BigDecimal avg(List<BigDecimal> v) {
        List<BigDecimal> xs = v.stream().filter(java.util.Objects::nonNull).toList();
        if (xs.isEmpty()) return null;
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(xs.size()), 2, RoundingMode.HALF_UP);
    }
}
