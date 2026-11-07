package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cqafrdm.evaluation.TestData.MAPPER;
import static com.cqafrdm.evaluation.TestData.checklist;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Оценка DEV-набора без бэкенда и БД + сравнение с ручной разметкой.
 *
 * 1) Сохраните ответ GET организаторов в backend/dev.json
 * 2) mvnw.cmd test "-Dtest=DevBatchTest" "-Ddev=true" "-Dlimit=10"
 *      -Dlimit=N        сколько диалогов (по умолчанию 10; 0 = все)
 *      -Dids=a,b,c      только эти conversation_id
 *    -> target/dev-eval.csv   ответы AI по каждому критерию
 *    -> manual.csv            шаблон для ручной оценки (создаётся, если его нет)
 * 3) Заполните manual.csv (YES / NO / NA / число шкалы) и запустите ещё раз:
 *    -> в консоли: совпадение AI и человека по каждому критерию и в целом.
 */
class DevBatchTest {

    @Test
    void evaluateDevAndCompareWithManual() throws Exception {
        assumeTrue(Boolean.getBoolean("dev"), "добавьте -Ddev=true");
        Path data = Path.of(System.getProperty("data", "dev.json"));
        assumeTrue(Files.exists(data), "нет файла " + data.toAbsolutePath());

        Map<String, String> env = readEnv(Path.of(".env"));
        String key = env.getOrDefault("LLM_API_KEY", "");
        String fbUrl = env.get("LLM_FALLBACK_BASE_URL"), fbKey = env.get("LLM_FALLBACK_API_KEY"), fbModel = env.get("LLM_FALLBACK_MODEL");
        LlmClient llm = AiConfig.buildClient(env.get("LLM_BASE_URL"), key, env.get("LLM_MODEL"), fbUrl, fbKey, fbModel);
        String vModel = env.getOrDefault("LLM_VERIFIER_MODEL", "");
        ScoringEngine engine = new ScoringEngine();
        EvaluationRunner runner = new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine),
                engine, AiConfig.num(env.get("LLM_PRICE_IN_PER_1M")), AiConfig.num(env.get("LLM_PRICE_OUT_PER_1M")), 1,
                vModel.isBlank() ? null : new AnswerVerifier(
                        AiConfig.buildClient(env.get("LLM_BASE_URL"), key, vModel, fbUrl, fbKey, fbModel),
                        new PromptBuilder(), MAPPER));

        List<Criterion> criteria = checklist();
        List<JsonNode> records = loadRecords(data);
        int limit = Integer.getInteger("limit", 10);
        String ids = System.getProperty("ids", "");
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode r : records) {
            String id = r.path("conversation_id").asText();
            if (!ids.isBlank() && !List.of(ids.split(",")).contains(id)) continue;
            selected.add(r);
            if (ids.isBlank() && limit > 0 && selected.size() >= limit) break;
        }
        System.out.println("\nDEV: всего " + records.size() + ", оцениваем " + selected.size() + ", модель " + llm.modelName());

        // ---- оценка
        StringBuilder csv = new StringBuilder("conversation_id;language;channel;status;total;gross");
        criteria.forEach(c -> csv.append(';').append(c.id()));
        csv.append(";needs_review;seconds;error\n");
        Map<String, Map<String, String>> ai = new LinkedHashMap<>();
        double sumCost = 0; long sumMs = 0; int ok = 0;
        for (JsonNode r : selected) {
            String id = r.path("conversation_id").asText();
            List<TranscriptMessage> t = MAPPER.convertValue(r.path("transcript"), new TypeReference<List<TranscriptMessage>>() {});
            long start = System.currentTimeMillis();
            EvaluationOutcome o = runner.evaluate(id, r.path("channel").asText(), r.path("language").asText(), t, criteria);
            long ms = System.currentTimeMillis() - start;
            sumMs += ms; sumCost += o.costUsd();
            Map<String, String> answers = new LinkedHashMap<>();
            int review = 0;
            if (o.result() != null) {
                ok++;
                for (CriterionResult c : o.result().criteria()) {
                    answers.put(c.criterionId(), c.isNa() ? "NA" : c.answer());
                    if (Boolean.TRUE.equals(c.needsReview())) review++;
                }
            }
            ai.put(id, answers);
            csv.append(id).append(';').append(r.path("language").asText()).append(';').append(r.path("channel").asText())
               .append(';').append(o.status()).append(';').append(o.totalScore() == null ? "" : o.totalScore())
               .append(';').append(o.grossError() == null ? "" : o.grossError());
            criteria.forEach(c -> csv.append(';').append(answers.getOrDefault(c.id(), "")));
            csv.append(';').append(review).append(';').append(ms / 1000).append(';')
               .append(o.error() == null ? "" : o.error().replace(';', ',').replace('\n', ' ')).append('\n');
            System.out.printf("%-14s %-3s %-6s total=%-6s gross=%-5s review=%d %4ds %s%n", id, r.path("language").asText(),
                    o.status(), o.totalScore(), o.grossError(), review, ms / 1000, answers.values());
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/dev-eval.csv"), "﻿" + csv, StandardCharsets.UTF_8);
        System.out.printf("%nOK %d/%d | среднее время %.1f с | стоимость всего $%.4f, на оценку $%.5f%n",
                ok, selected.size(), sumMs / 1000.0 / Math.max(1, selected.size()), sumCost, sumCost / Math.max(1, selected.size()));
        System.out.println("Результат: " + Path.of("target/dev-eval.csv").toAbsolutePath());

        // ---- ручная разметка
        Path manual = Path.of("manual.csv");
        if (!Files.exists(manual)) {
            StringBuilder m = new StringBuilder("conversation_id");
            criteria.forEach(c -> m.append(';').append(c.id()));
            m.append(";comment\n");
            for (String id : ai.keySet()) m.append(id).append(";".repeat(criteria.size() + 1)).append('\n');
            Files.writeString(manual, "﻿" + m, StandardCharsets.UTF_8);
            System.out.println("Создан шаблон " + manual.toAbsolutePath() + " — заполните YES/NO/NA/число и запустите снова.");
            return;
        }
        compare(manual, ai, criteria);
    }

    private void compare(Path manual, Map<String, Map<String, String>> ai, List<Criterion> criteria) throws Exception {
        List<String> lines = Files.readAllLines(manual, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return;
        String[] head = lines.get(0).replace("﻿", "").split("[;,]", -1);
        Map<String, int[]> per = new LinkedHashMap<>();       // criterionId -> {совпало, всего}
        criteria.forEach(c -> per.put(c.id(), new int[2]));
        List<String> diffs = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] v = line.split("[;,]", -1);
            if (v.length < 2 || !ai.containsKey(v[0].trim())) continue;
            Map<String, String> a = ai.get(v[0].trim());
            for (int i = 1; i < Math.min(v.length, head.length); i++) {
                String cid = head[i].trim(), human = norm(v[i]);
                if (!per.containsKey(cid) || human.isEmpty() || a.isEmpty()) continue;
                String model = norm(a.getOrDefault(cid, ""));
                per.get(cid)[1]++;
                if (human.equals(model)) per.get(cid)[0]++;
                else diffs.add(v[0].trim() + " " + cid + ": человек=" + human + ", AI=" + model);
            }
        }
        int match = 0, total = 0;
        System.out.println("\nСОВПАДЕНИЕ AI С ЧЕЛОВЕКОМ");
        for (var e : per.entrySet()) {
            int[] x = e.getValue();
            if (x[1] == 0) continue;
            match += x[0]; total += x[1];
            System.out.printf("  %-4s %3d/%-3d %5.1f%%%n", e.getKey(), x[0], x[1], 100.0 * x[0] / x[1]);
        }
        if (total > 0) System.out.printf("  ИТОГО %d/%d = %.1f%%%n", match, total, 100.0 * match / total);
        if (!diffs.isEmpty()) {
            System.out.println("Расхождения (для правки промпта):");
            diffs.forEach(d -> System.out.println("  " + d));
        }
    }

    private static String norm(String s) {
        String t = s == null ? "" : s.trim().toUpperCase();
        return switch (t) {
            case "N/A", "НЕПРИМЕНИМО" -> "NA";
            case "ДА", "ИӘ", "Y" -> "YES";
            case "НЕТ", "ЖОҚ", "N" -> "NO";
            default -> t;
        };
    }

    private static List<JsonNode> loadRecords(Path p) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8).replace("﻿", ""));
        JsonNode recs = root.has("data") ? root.path("data").path("records") : root.has("records") ? root.path("records") : root;
        List<JsonNode> out = new ArrayList<>();
        recs.forEach(out::add);
        return out;
    }

    private static Map<String, String> readEnv(Path p) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(p)) return m;
        for (String line : Files.readAllLines(p)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
            int i = s.indexOf('=');
            m.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
        }
        return m;
    }
}
