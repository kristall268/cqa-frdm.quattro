package com.cqafrdm.evaluation.ai;

import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cqafrdm.evaluation.TestData.MAPPER;
import static com.cqafrdm.evaluation.TestData.chat;
import static com.cqafrdm.evaluation.TestData.checklist;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Настоящий вызов модели из backend/.env. Обычный `mvnw test` его пропускает.
 * Запуск (Windows):  mvnw.cmd test "-Dtest=LiveGeminiTest" "-Dlive=true"
 */
class LiveGeminiTest {

    @Test
    void evaluatesRealChatsWithModelFromEnv() throws Exception {
        assumeTrue(Boolean.getBoolean("live"), "добавьте -Dlive=true");
        Map<String, String> env = readEnv(Path.of(".env"));
        String key = env.getOrDefault("LLM_API_KEY", "");
        assumeTrue(!key.isBlank(), "LLM_API_KEY пуст в backend/.env");

        String fbUrl = env.get("LLM_FALLBACK_BASE_URL"), fbKey = env.get("LLM_FALLBACK_API_KEY"), fbModel = env.get("LLM_FALLBACK_MODEL");
        LlmClient llm = AiConfig.buildClient(env.get("LLM_BASE_URL"), key, env.get("LLM_MODEL"), fbUrl, fbKey, fbModel);
        String vModel = env.getOrDefault("LLM_VERIFIER_MODEL", "");
        ScoringEngine engine = new ScoringEngine();
        EvaluationRunner runner = new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine),
                engine, AiConfig.num(env.get("LLM_PRICE_IN_PER_1M")), AiConfig.num(env.get("LLM_PRICE_OUT_PER_1M")), 1,
                vModel.isBlank() ? null : new AnswerVerifier(
                        AiConfig.buildClient(env.get("LLM_BASE_URL"), key, vModel, fbUrl, fbKey, fbModel),
                        new PromptBuilder(), MAPPER));

        run(runner, "RU-sample", "RU", chat());
        run(runner, "KZ-sample", "KZ", List.of(
                new TranscriptMessage("00:00", "system", "Чат басталды"),
                new TranscriptMessage("00:04", "client", "Сәлеметсіз бе, шотымдағы қалдықты білгім келеді"),
                new TranscriptMessage("00:30", "operator", "Сәлеметсіз бе! Шотыңызда 250 000 теңге бар."),
                new TranscriptMessage("00:50", "client", "Неге ақшам аз? Бұл дұрыс емес, ашуланып тұрмын"),
                new TranscriptMessage("01:20", "operator", "Жеке маған аударсаңыз, өзім пайдалы жерге салып беремін"),
                new TranscriptMessage("01:40", "system", "Чат жабылды")));
        // ожидание для KZ: c1 Жоқ (не представился), c3 Жоқ (баланс без идентификации), c8 ДА (личный перевод)
    }

    private void run(EvaluationRunner runner, String id, String lang, List<TranscriptMessage> t) throws Exception {
        long start = System.currentTimeMillis();
        EvaluationOutcome o = runner.evaluate(id, "agent chat", lang, t, checklist());
        System.out.println("\n===== " + id + " | " + o.status() + " | total=" + o.totalScore() + " | gross=" + o.grossError()
                + " | model=" + o.model() + " | tokens=" + o.inputTokens() + "/" + o.outputTokens()
                + " | cost=$" + o.costUsd() + " | calls=" + o.llmCalls()
                + " | " + (System.currentTimeMillis() - start) + " ms");
        if (o.error() != null) System.out.println("ERROR: " + o.error() + "\nRAW: " + o.rawResponse());
        else o.result().criteria().forEach(c -> System.out.println(c.criterionId() + " " + (c.isNa() ? "N/A" : c.answer())
                + " pts=" + c.points() + " ev=" + c.evidenceIds() + (c.checkedRange() == null ? "" : " range=" + c.checkedRange())
                + " | " + c.reason()
                + (c.verification() == null ? "" : "\n      -> " + c.verification())));
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
    }

    private static Map<String, String> readEnv(Path p) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(p)) return m;
        for (String line : Files.readAllLines(p)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) continue;
            int i = s.indexOf('=');
            String v = s.substring(i + 1).trim();
            int hash = v.indexOf(" #");
            if (hash >= 0) v = v.substring(0, hash).trim();
            m.put(s.substring(0, i).trim(), v);
        }
        return m;
    }
}
