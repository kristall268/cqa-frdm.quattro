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
 * Настоящий вызов локальной Ollama из backend/.env (LLM_BASE_URL=http://localhost:11434, LLM_MODEL=…).
 * Обычный `mvnw test` его пропускает. Запуск (Windows):  mvnw.cmd test "-Dtest=LiveOllamaTest" "-Dlive=true"
 */
class LiveOllamaTest {

    @Test
    void evaluatesRealChatsWithLocalOllama() throws Exception {
        assumeTrue(Boolean.getBoolean("live"), "добавьте -Dlive=true");
        Map<String, String> env = readEnv(Path.of(".env"));
        assumeTrue(AiConfig.isOllama(env::get, "LLM_"), "в backend/.env основная модель не Ollama (LLM_BASE_URL с портом 11434)");

        LlmClient llm = AiConfig.chain(env::get, null, null, null);
        assumeTrue(llm instanceof OllamaLlmClient || llm instanceof CompositeLlmClient, "цепочка не собрана");
        ScoringEngine engine = new ScoringEngine();
        EvaluationRunner runner = AiConfig.runner(env::get, llm, engine);
        System.out.println("LLM chain: " + AiConfig.describe(env::get) + " | verifier: " + env.getOrDefault("LLM_VERIFIER_MODEL", "-"));

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
