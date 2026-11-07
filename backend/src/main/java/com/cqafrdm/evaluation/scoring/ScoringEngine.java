package com.cqafrdm.evaluation.scoring;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.evaluation.EvaluationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Чистый расчёт баллов по правилам чек-листа. Без LLM, без БД, без Spring — детерминирован.
 *
 * <pre>
 * BINARY: YES -> вес, NO -> 0
 * SCALE : вес * ступень / максимум шкалы
 * N/A   : исключается и из суммы баллов, и из суммы весов (вес перераспределяется)
 * FLAG  : баллов нет; YES -> grossError = true (оценку не обнуляет)
 * итог  = 100 * Σбаллов / Σприменимых весов, округление до 2 знаков HALF_UP;
 *         null, если применимых весовых критериев нет
 * </pre>
 */
public class ScoringEngine {

    public static final String YES = "YES";
    public static final String NO = "NO";

    /** Проверяет, что ответ допустим для критерия. Бросает IllegalArgumentException с понятным текстом. */
    public void validateAnswer(Criterion c, String answer, boolean isNa) {
        if (isNa) {
            if (!c.naAllowed()) throw new IllegalArgumentException(c.id() + ": N/A не разрешён для этого критерия");
            return;
        }
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException(c.id() + ": пустой ответ");
        switch (c.type()) {
            case BINARY, FLAG -> {
                if (!YES.equals(answer) && !NO.equals(answer))
                    throw new IllegalArgumentException(c.id() + ": ожидается YES или NO, получено " + answer);
            }
            case SCALE -> {
                int v;
                try {
                    v = Integer.parseInt(answer.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(c.id() + ": ожидается число шкалы, получено " + answer);
                }
                if (!hasScaleValue(c, v))
                    throw new IllegalArgumentException(c.id() + ": значение " + v + " не входит в шкалу "
                            + c.scale().stream().map(s -> String.valueOf(s.value())).toList());
            }
        }
    }

    /** Баллы за один критерий. null для N/A и FLAG. */
    public Double points(Criterion c, String answer, boolean isNa) {
        validateAnswer(c, answer, isNa);
        if (isNa || c.type() == CriterionType.FLAG) return null;
        if (c.type() == CriterionType.BINARY) return YES.equals(answer) ? w(c) : 0.0;
        int max = maxScale(c);
        if (max <= 0) return 0.0;
        return w(c) * Integer.parseInt(answer.trim()) / max;
    }

    /**
     * Считает баллы и итог. Порядок результата = порядок критериев чек-листа.
     * Каждый критерий должен иметь ровно один результат.
     */
    public EvaluationResult compute(List<Criterion> criteria, List<CriterionResult> results) {
        Map<String, CriterionResult> byId = indexResults(results);
        List<CriterionResult> scored = new ArrayList<>();
        double sumPoints = 0, sumWeights = 0;
        boolean gross = false;
        for (Criterion c : criteria) {
            CriterionResult r = byId.get(c.id());
            if (r == null) throw new IllegalArgumentException(c.id() + ": нет результата для критерия");
            Double p = points(c, r.answer(), r.isNa());
            scored.add(new CriterionResult(c.id(), c.title(), c.type(), c.weight(),
                    r.isNa() ? null : r.answer(), r.isNa(), p == null ? null : bd2(p),
                    r.reason(), r.evidenceIds() == null ? List.of() : r.evidenceIds(), r.checkedRange()));
            if (c.type() == CriterionType.FLAG) {
                gross |= !r.isNa() && YES.equals(r.answer());
            } else if (!r.isNa()) {
                sumPoints += p;
                sumWeights += w(c);
            }
        }
        BigDecimal total = sumWeights > 0 ? bd2(100.0 * sumPoints / sumWeights) : null;
        return new EvaluationResult(scored, total, gross);
    }

    /**
     * Решение проверяющего по апелляции: меняет один критерий и пересчитывает итог.
     * Исходный result не изменяется (records неизменяемы) — возвращается новый.
     */
    public EvaluationResult applyOverride(List<Criterion> criteria, EvaluationResult base,
                                          String criterionId, String newAnswer, boolean newIsNa, String reviewerReason) {
        boolean found = false;
        List<CriterionResult> changed = new ArrayList<>();
        for (CriterionResult r : base.criteria()) {
            if (r.criterionId().equals(criterionId)) {
                found = true;
                String reason = reviewerReason == null || reviewerReason.isBlank()
                        ? r.reason() : "Решение проверяющего: " + reviewerReason;
                changed.add(new CriterionResult(r.criterionId(), r.title(), r.type(), r.weight(),
                        newIsNa ? null : newAnswer, newIsNa, null, reason, r.evidenceIds(), r.checkedRange()));
            } else {
                changed.add(r);
            }
        }
        if (!found) throw new IllegalArgumentException(criterionId + ": критерий не найден в оценке");
        return compute(criteria, changed);
    }

    /** Проверка чек-листа перед сохранением новой версии (для POST /api/checklists). */
    public List<String> validateChecklist(List<Criterion> criteria) {
        List<String> errors = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) {
            errors.add("Чек-лист пуст");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (Criterion c : criteria) {
            String id = c.id() == null ? "?" : c.id();
            if (c.id() == null || c.id().isBlank()) errors.add("Критерий без id");
            else if (!ids.add(c.id())) errors.add(id + ": повторяющийся id");
            if (c.title() == null || c.title().isBlank()) errors.add(id + ": пустое название");
            if (c.type() == null) {
                errors.add(id + ": не указан тип");
                continue;
            }
            switch (c.type()) {
                case FLAG -> {
                    if (w(c) != 0) errors.add(id + ": у FLAG вес должен быть 0");
                }
                case BINARY -> {
                    if (w(c) <= 0) errors.add(id + ": вес должен быть > 0");
                }
                case SCALE -> {
                    if (w(c) <= 0) errors.add(id + ": вес должен быть > 0");
                    if (c.scale() == null || c.scale().size() < 2) errors.add(id + ": у шкалы минимум 2 ступени");
                    else {
                        if (maxScale(c) <= 0) errors.add(id + ": максимум шкалы должен быть > 0");
                        if (c.scale().stream().map(s -> s.value()).distinct().count() != c.scale().size())
                            errors.add(id + ": ступени шкалы повторяются");
                        if (c.scale().stream().anyMatch(s -> s.value() < 0))
                            errors.add(id + ": ступени шкалы не могут быть отрицательными");
                    }
                }
            }
        }
        if (criteria.stream().filter(c -> c.type() != null && c.type() != CriterionType.FLAG).findAny().isEmpty())
            errors.add("Нет ни одного критерия с весом");
        return errors;
    }

    /** Вес критерия как double (в чек-листе хранится BigDecimal). */
    public static double w(Criterion c) {
        return c.weight() == null ? 0 : c.weight().doubleValue();
    }

    public static int maxScale(Criterion c) {
        return c.scale() == null ? 0 : c.scale().stream().mapToInt(s -> s.value()).max().orElse(0);
    }

    public static boolean hasScaleValue(Criterion c, int v) {
        return c.scale() != null && c.scale().stream().anyMatch(s -> s.value() == v);
    }

    /** Округление до 2 знаков HALF_UP (как в чек-листе), тип для JSON/БД. */
    public static BigDecimal bd2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, CriterionResult> indexResults(List<CriterionResult> results) {
        Map<String, CriterionResult> m = results.stream()
                .collect(Collectors.toMap(CriterionResult::criterionId, Function.identity(), (a, b) -> {
                    throw new IllegalArgumentException(a.criterionId() + ": результат указан дважды");
                }));
        return m;
    }
}
