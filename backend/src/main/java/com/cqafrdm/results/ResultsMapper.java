package com.cqafrdm.results;

import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.evaluation.CheckedRange;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.EvaluationResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Перевод внутренней оценки в формат сдачи результатов (CQA Student Guide, приложение «Формат JSON 1.0»).
 * <ul>
 *   <li>коды критериев: c1..c8 -> contact, discovery, identification, consultation, empathy, dialogue, closure, critical;</li>
 *   <li>value: true = «Да», false = «Нет», null = N/A, число = ступень шкалы (до учёта веса);</li>
 *   <li>evidence_turns: нумерация с 1 по исходному массиву транскрипта (внутри системы индексы с 0);</li>
 *   <li>final_score: 0..100, 2 знака HALF_UP, N/A исключается с перераспределением веса;</li>
 *   <li>critical_flag = value критерия critical, итог не обнуляет.</li>
 * </ul>
 * Чистый класс без Spring — покрыт тестами.
 */
public final class ResultsMapper {

    public static final List<String> CODES = List.of(
            "contact", "discovery", "identification", "consultation", "empathy", "dialogue", "closure", "critical");

    private static final Map<String, String> CODE_BY_ID = Map.of(
            "c1", "contact", "c2", "discovery", "c3", "identification", "c4", "consultation",
            "c5", "empathy", "c6", "dialogue", "c7", "closure", "c8", "critical");

    private ResultsMapper() {}

    /** Код критерия в формате сдачи; null — критерий не входит в восемь обязательных (например, добавленный c9). */
    public static String code(String criterionId) {
        if (criterionId == null) return null;
        String id = criterionId.trim().toLowerCase();
        return CODES.contains(id) ? id : CODE_BY_ID.get(id);
    }

    /** Запись об ошибке: ID сохраняется, criteria пустой, score = null, причина в error. */
    public static Map<String, Object> error(String conversationId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversation_id", conversationId);
        m.put("status", "error");
        m.put("criteria", List.of());
        m.put("score", null);
        m.put("error", message == null || message.isBlank() ? "Не удалось оценить диалог" : message);
        return m;
    }

    /** Запись по успешной оценке; если каких-то из 8 критериев нет — запись об ошибке (не подменяем данные). */
    public static Map<String, Object> toEntry(String conversationId, EvaluationResult result) {
        if (result == null || result.criteria() == null) return error(conversationId, "Нет результата оценки");
        Map<String, CriterionResult> byCode = new LinkedHashMap<>();
        for (CriterionResult r : result.criteria()) {
            String c = code(r.criterionId());
            if (c != null && !byCode.containsKey(c)) byCode.put(c, r);
        }
        List<String> missing = CODES.stream().filter(c -> !byCode.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            return error(conversationId, "В версии чек-листа нет обязательных критериев: " + String.join(", ", missing));
        }

        List<Map<String, Object>> criteria = new ArrayList<>();
        BigDecimal sumPoints = BigDecimal.ZERO, sumWeights = BigDecimal.ZERO;
        Boolean critical = false;
        for (String code : CODES) {
            CriterionResult r = byCode.get(code);
            Object value = value(r);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("criterion_id", code);
            c.put("value", value);
            c.put("rationale", r.reason() == null ? "" : r.reason().trim());
            c.put("evidence_turns", turns(r.evidenceIds(), r.checkedRange()));
            criteria.add(c);

            if (r.type() == CriterionType.FLAG) {
                critical = Boolean.TRUE.equals(value);
            } else if (!r.isNa() && r.weight() != null && r.weight().signum() > 0) {
                sumWeights = sumWeights.add(r.weight());
                sumPoints = sumPoints.add(r.points() == null ? BigDecimal.ZERO : r.points());
            }
        }
        BigDecimal finalScore = sumWeights.signum() == 0 ? null
                : sumPoints.multiply(BigDecimal.valueOf(100)).divide(sumWeights, 2, RoundingMode.HALF_UP);

        Map<String, Object> score = new LinkedHashMap<>();
        score.put("final_score", finalScore);
        score.put("critical_flag", critical);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversation_id", conversationId);
        m.put("status", "ok");
        m.put("criteria", criteria);
        m.put("score", score);
        return m;
    }

    /** true/false/null/число по типу критерия. */
    static Object value(CriterionResult r) {
        if (r.isNa() || r.answer() == null) return null;
        if (r.type() == CriterionType.SCALE) {
            try {
                return Integer.parseInt(r.answer().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return "YES".equalsIgnoreCase(r.answer());
    }

    /**
     * Номера реплик с 1. Если доказательств нет, но указан проверенный участок — его границы
     * (для короткого участка — все реплики).
     */
    static List<Integer> turns(List<Integer> evidenceIds, CheckedRange range) {
        TreeSet<Integer> set = new TreeSet<>();
        if (evidenceIds != null) evidenceIds.stream().filter(i -> i != null && i >= 0).forEach(i -> set.add(i + 1));
        if (set.isEmpty() && range != null && range.from() >= 0 && range.to() >= range.from()) {
            if (range.to() - range.from() <= 5) {
                for (int i = range.from(); i <= range.to(); i++) set.add(i + 1);
            } else {
                set.add(range.from() + 1);
                set.add(range.to() + 1);
            }
        }
        return new ArrayList<>(set);
    }
}
