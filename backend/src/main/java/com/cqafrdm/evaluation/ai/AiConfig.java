package com.cqafrdm.evaluation.ai;

import com.cqafrdm.evaluation.scoring.ScoringEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Spring-бины модуля оценки. Настройки читаются из application.yaml (cqa.llm.*) или напрямую из .env.
 * Если LLM_API_KEY пуст — подключается MockLlmClient (приложение работает без модели).
 * Ключ никогда не логируется.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** Свой ObjectMapper модуля, чтобы не зависеть от версии Jackson в Spring Boot. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Bean
    public ScoringEngine scoringEngine() {
        return new ScoringEngine();
    }

    /** Провайдеры по порядку: основной и до четырёх резервных. У каждого MODEL может быть списком через запятую. */
    static final String[] PROVIDERS = {"LLM_", "LLM_FALLBACK_", "LLM_FALLBACK2_", "LLM_FALLBACK3_", "LLM_FALLBACK4_"};

    @Bean
    public LlmClient llmClient(Environment env) {
        LlmClient c = chain(env::getProperty, null, null, null);
        if (c instanceof OllamaLlmClient o) log.info("Локальная модель Ollama: {} ({})", o.modelName(), o.baseUrl());
        if (c instanceof MockLlmClient) log.warn("LLM_API_KEY/LLM_BASE_URL не заданы — используется MockLlmClient (тестовые оценки)");
        else log.info("LLM chain: {}", describe(env::getProperty));
        return c;
    }

    @Bean
    public EvaluationRunner evaluationRunner(LlmClient llmClient, ScoringEngine scoringEngine, Environment env) {
        return runner(env::getProperty, llmClient, scoringEngine);
    }

    /** Сборка runner'а из переменных .env (используется и в Spring, и в DevBatchTest/LiveGeminiTest). */
    public static EvaluationRunner runner(Function<String, String> env, LlmClient llm, ScoringEngine engine) {
        AnswerVerifier verifier = null;
        String vModel = env.apply("LLM_VERIFIER_MODEL");
        if (!blank(vModel) && !(llm instanceof MockLlmClient)) {
            verifier = new AnswerVerifier(chain(env, vModel, env.apply("LLM_VERIFIER_BASE_URL"), env.apply("LLM_VERIFIER_API_KEY")),
                    new PromptBuilder(), MAPPER);
            log.info("LLM verifier: {}", vModel);
        }
        // Ollama обычно обрабатывает запросы по очереди: лишние параллельные вызовы ждут в её очереди до таймаута
        String parallelDefault = isOllama(env, "LLM_") ? "1" : "2";
        EvaluationRunner runner = new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), engine,
                num(env.apply("LLM_PRICE_IN_PER_1M")), num(env.apply("LLM_PRICE_OUT_PER_1M")),
                (int) Math.max(1, num(or(env.apply("LLM_MAX_PARALLEL"), parallelDefault))), verifier);

        // CQA Guard: арбитр (3-я модель) решает только спорные критерии; без проверяющего не нужен
        String aModel = env.apply("LLM_ARBITER_MODEL");
        if (verifier != null && !blank(aModel)) {
            runner.withArbiter(new ConsensusArbiter(chain(env, aModel, env.apply("LLM_ARBITER_BASE_URL"),
                    env.apply("LLM_ARBITER_API_KEY")), new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), MAPPER));
            log.info("LLM arbiter: {}", aModel);
        }
        // Гибрид: звонки (реальные записи) — локальной моделью, данные не уходят в облако
        String lUrl = env.apply("LLM_LOCAL_BASE_URL"), lModel = env.apply("LLM_LOCAL_MODEL");
        if (!blank(lUrl) && !blank(lModel)) {
            String lKey = or(env.apply("LLM_LOCAL_API_KEY"), "local");
            runner.withLocalForCalls(ollama(env, "LLM_LOCAL_", lUrl)
                    ? new OllamaLlmClient(MAPPER, lUrl.trim(), lModel.trim(), env)
                    : timed(new OpenAiCompatibleLlmClient(MAPPER, lUrl.trim(), lKey.trim(), lModel.trim()), env));
            log.info("Hybrid: voice calls -> local {}", lModel);
        }
        runner.withPiiMasking(!"false".equalsIgnoreCase(or(env.apply("PII_MASKING"), "true")));
        return runner;
    }

    /**
     * Цепочка клиентов из LLM_*, LLM_FALLBACK_*, LLM_FALLBACK2_* (BASE_URL, API_KEY, MODEL).
     * modelOverride/urlOverride/keyOverride — для проверяющей модели (подменяют только первого провайдера).
     * Все, кроме последнего, работают в режиме fastFail: не ждут перегруженный сервис.
     */
    public static LlmClient chain(Function<String, String> env, String modelOverride, String urlOverride, String keyOverride) {
        List<LlmClient> list = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.length; i++) {
            String p = PROVIDERS[i];
            String url = env.apply(p + "BASE_URL"), key = env.apply(p + "API_KEY"), model = env.apply(p + "MODEL");
            if (i == 0) {
                if (!blank(urlOverride)) url = urlOverride;
                if (!blank(keyOverride)) key = keyOverride;
                if (!blank(modelOverride)) model = modelOverride;
            }
            if (ollama(env, p, url)) {
                // Ollama — нативный API: размер окна (num_ctx) в каждом запросе, JSON-режим, seed; ключ не нужен
                if (blank(model)) continue;
                list.add(new OllamaLlmClient(MAPPER, blank(url) ? "http://localhost:11434" : url.trim(), model.trim(), env));
                continue;
            }
            if (blank(key) && isLocal(url)) key = "local";   // LM Studio / vLLM на localhost ключа не требуют
            if (blank(url) || blank(key) || blank(model)) continue;
            list.add(timed(new OpenAiCompatibleLlmClient(MAPPER, url.trim(), key.trim(), model.trim()), env));
        }
        if (list.isEmpty()) return new MockLlmClient(MAPPER);
        if (list.size() == 1) return list.get(0);
        for (int i = 0; i < list.size() - 1; i++) if (list.get(i) instanceof OpenAiCompatibleLlmClient o) o.fastFail();
        return new CompositeLlmClient(list);
    }

    /** Старый вариант API (основной + один резерв). */
    public static LlmClient buildClient(String baseUrl, String apiKey, String model,
                                        String fbUrl, String fbKey, String fbModel) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("LLM_BASE_URL", baseUrl); m.put("LLM_API_KEY", apiKey); m.put("LLM_MODEL", model);
        m.put("LLM_FALLBACK_BASE_URL", fbUrl); m.put("LLM_FALLBACK_API_KEY", fbKey); m.put("LLM_FALLBACK_MODEL", fbModel);
        return chain(m::get, null, null, null);
    }

    static String describe(Function<String, String> env) {
        List<String> parts = new ArrayList<>();
        for (String p : PROVIDERS) {
            if (blank(env.apply(p + "MODEL"))) continue;
            if (ollama(env, p, env.apply(p + "BASE_URL"))) parts.add(env.apply(p + "MODEL") + " (Ollama)");
            else if (!blank(env.apply(p + "API_KEY")) || isLocal(env.apply(p + "BASE_URL"))) parts.add(env.apply(p + "MODEL"));
        }
        return String.join(" -> ", parts);
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * Провайдер — Ollama: явно {prefix}PROVIDER=ollama, либо адрес похож на Ollama (порт 11434).
     * {prefix}PROVIDER=openai оставляет OpenAI-совместимый режим (например, Ollama через /v1 с Modelfile).
     */
    static boolean ollama(Function<String, String> env, String prefix, String url) {
        String p = env.apply(prefix + "PROVIDER");
        if (!blank(p)) return "ollama".equalsIgnoreCase(p.trim());
        return OllamaLlmClient.looksLikeOllama(url);
    }

    static boolean isOllama(Function<String, String> env, String prefix) {
        return !blank(env.apply(prefix + "MODEL")) && ollama(env, prefix, env.apply(prefix + "BASE_URL"));
    }

    /** LLM_TIMEOUT_SECONDS — таймаут одного HTTP-запроса к облачной модели (по умолчанию 120). */
    static OpenAiCompatibleLlmClient timed(OpenAiCompatibleLlmClient c, Function<String, String> env) {
        int t = (int) num(env.apply("LLM_TIMEOUT_SECONDS"));
        return t > 0 ? c.timeoutSeconds(t) : c;
    }

    /** Локальная модель (Ollama, LM Studio, vLLM) — данные не покидают компьютер. */
    static boolean isLocal(String url) {
        if (blank(url)) return false;
        String u = url.toLowerCase();
        return u.contains("://localhost") || u.contains("://127.0.0.1") || u.contains("://host.docker.internal");
    }

    private static String or(String a, String b) { return blank(a) ? b : a; }

    /** Пустая строка в .env -> 0, без падения приложения. */
    static double num(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return 0; }
    }
}
