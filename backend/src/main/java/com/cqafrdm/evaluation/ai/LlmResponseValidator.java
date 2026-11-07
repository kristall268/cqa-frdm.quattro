package com.cqafrdm.evaluation.ai;

import com.cqafrdm.evaluation.model.CheckedRange;
import com.cqafrdm.evaluation.model.Criterion;
import com.cqafrdm.evaluation.model.CriterionResult;
import com.cqafrdm.evaluation.model.CriterionType;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Проверяет ответ модели. Ничего не «додумывает»: если ответ неполный или ссылается
 * на несуществующие реплики — возвращает ошибки (runner делает 1 повтор, затем FAILED).
 */
public class LlmResponseValidator {

    public record Result(List<CriterionResult> results, List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private final ObjectMapper mapper;
    private final ScoringEngine engine;

    public LlmResponseValidator(ObjectMapper mapper, ScoringEngine engine) {
        this.mapper = mapper;
        this.engine = engine;
    }

    public Result validate(String content, List<Criterion> criteria, List<TranscriptMessage> transcript) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(content));
        } catch (Exception e) {
            return new Result(List.of(), List.of("Ответ не является корректным JSON: " + e.getMessage()));
        }
        JsonNode arr = root == null ? null : root.isArray() ? root : root.get("criteria");
        if (arr == null || !arr.isArray()) return new Result(List.of(), List.of("Нет массива \"criteria\""));

        Map<String, Criterion> byId = new LinkedHashMap<>();
        criteria.forEach(c -> byId.put(c.id(), c));
        Map<String, CriterionResult> parsed = new HashMap<>();
        int n = transcript.size();

        for (JsonNode item : arr) {
            String id = text(item, "criterion_id");
            Criterion c = id == null ? null : byId.get(id);
            if (c == null) { errors.add("Неизвестный criterion_id: " + id); continue; }
            if (parsed.containsKey(id)) { errors.add(id + ": указан дважды"); continue; }

            String answer = normalizeAnswer(item.get("answer"), c);
            boolean isNa = item.path("is_na").asBoolean(false) || "NA".equals(answer);
            if (isNa) answer = null;
            String reason = text(item, "reason");
            List<Integer> evidence = new ArrayList<>();
            JsonNode ev = item.get("evidence_ids");
            if (ev != null && ev.isArray()) for (JsonNode e : ev) {
                if (!e.canConvertToInt()) { errors.add(id + ": evidence_id не число: " + e); continue; }
                int i = e.asInt();
                if (i < 0 || i >= n) errors.add(id + ": реплики [" + i + "] не существует (всего " + n + ")");
                else if (!evidence.contains(i)) evidence.add(i);
            }
            CheckedRange range = null;
            JsonNode r = item.get("checked_range");
            if (r != null && r.isObject() && r.has("from") && r.has("to")) {
                int from = r.get("from").asInt(-1), to = r.get("to").asInt(-1);
                if (from < 0 || to >= n || from > to) errors.add(id + ": неверный checked_range " + from + ".." + to);
                else range = new CheckedRange(from, to);
            }

            boolean answerOk = true;
            try {
                engine.validateAnswer(c, answer, isNa);
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
                answerOk = false;
            }
            if (reason == null || reason.isBlank()) errors.add(id + ": пустое обоснование (reason)");
            if (answerOk && !isNa) checkEvidence(c, answer, evidence, range, transcript, errors);

            parsed.put(id, new CriterionResult(id, c.title(), c.type(), c.weight(), answer, isNa, null,
                    reason, evidence, range));
        }
        for (String id : byId.keySet()) if (!parsed.containsKey(id)) errors.add(id + ": нет оценки по критерию");

        List<CriterionResult> ordered = new ArrayList<>();
        for (String id : byId.keySet()) if (parsed.containsKey(id)) ordered.add(parsed.get(id));
        return new Result(ordered, errors);
    }

    /** Доказательство обязательно: выполненное действие — реплика оператора; пропущенное — место/участок, где ожидалось. */
    private void checkEvidence(Criterion c, String answer, List<Integer> evidence, CheckedRange range,
                               List<TranscriptMessage> transcript, List<String> errors) {
        if (c.type() == CriterionType.FLAG) {
            if (ScoringEngine.YES.equals(answer) && evidence.isEmpty())
                errors.add(c.id() + ": грубая ошибка без реплики-доказательства");
            return;
        }
        boolean positive = c.type() == CriterionType.BINARY
                ? ScoringEngine.YES.equals(answer)
                : answer != null && Integer.parseInt(answer) > 0;
        if (positive) {
            boolean hasOperator = evidence.stream()
                    .anyMatch(i -> "operator".equalsIgnoreCase(transcript.get(i).speaker()));
            if (!hasOperator) errors.add(c.id() + ": для положительной оценки нужна реплика оператора в evidence_ids");
        } else if (evidence.isEmpty() && range == null) {
            errors.add(c.id() + ": для отрицательной оценки укажите evidence_ids или checked_range, где ожидалось действие");
        }
    }

    private static String normalizeAnswer(JsonNode a, Criterion c) {
        if (a == null || a.isNull()) return null;
        if (a.isNumber()) return String.valueOf(a.asInt());
        String s = a.asText().trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "N/A", "NA", "НЕПРИМЕНИМО" -> "NA";
            case "ДА", "ИӘ", "TRUE" -> "YES";
            case "НЕТ", "ЖОҚ", "FALSE" -> "NO";
            default -> {
                if (c.type() == CriterionType.SCALE && s.matches("\\d+(\\.0+)?")) yield s.replaceAll("\\.0+$", "");
                yield s;
            }
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a > 0 && b > a && !t.startsWith("[")) t = t.substring(a, b + 1);
        return t.trim();
    }
}
