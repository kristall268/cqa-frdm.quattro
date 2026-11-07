package com.cqafrdm.whatif;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What-if: пересчёт итога по НОВОЙ конфигурации чек-листа (веса, шкалы, N/A) БЕЗ повторного вызова модели.
 * Возможно, потому что модель выдаёт только ответы и доказательства, а баллы считает ScoringEngine.
 * Новые критерии (которых не было в оценке) и ответы, недопустимые в новой шкале, требуют повторной оценки AI —
 * они исключаются из пересчёта и перечисляются в note.
 */
public final class WhatIfCalculator {

    public record Item(String conversationId, BigDecimal before, BigDecimal after, BigDecimal delta,
                       boolean grossBefore, boolean grossAfter, String status, String note) {}

    private WhatIfCalculator() {}

    public static Item compute(String conversationId, EvaluationResult base, List<Criterion> criteria, ScoringEngine engine) {
        Map<String, CriterionResult> byId = new HashMap<>();
        base.criteria().forEach(c -> byId.put(c.criterionId(), c));
        List<Criterion> usable = new ArrayList<>();
        List<CriterionResult> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Criterion c : criteria) {
            CriterionResult r = byId.get(c.id());
            if (r == null) { notes.add(c.id() + ": новый критерий — нужна оценка AI"); continue; }
            try {
                engine.validateAnswer(c, r.answer(), r.isNa());
            } catch (IllegalArgumentException e) {
                notes.add(e.getMessage() + " — нужна оценка AI");
                continue;
            }
            usable.add(c);
            rows.add(r);
        }
        boolean hasWeighted = usable.stream().anyMatch(c -> c.type() != com.cqafrdm.checklist.CriterionType.FLAG);
        EvaluationResult after = hasWeighted ? engine.compute(usable, rows) : null;
        BigDecimal a = after == null ? null : after.totalScore();
        BigDecimal b = base.totalScore();
        BigDecimal d = a == null || b == null ? null : a.subtract(b);
        return new Item(conversationId, b, a, d, base.grossError(), after != null && after.grossError(),
                notes.isEmpty() ? "OK" : "PARTIAL", String.join("; ", notes));
    }
}
