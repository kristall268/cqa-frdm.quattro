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

    /** Провайдеры по порядку: основной и до двух резервных. У каждого MODEL может быть списком через запятую. */
    static final String[] PROVIDERS = {"LLM_", "LLM_FALLBACK_", "LLM_FALLBACK2_"};

    @Bean
    public LlmClient llmClient(Environment env) {
        LlmClient c = chain(env::getProperty, null, null, null);
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
        return new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), engine,
                num(env.apply("LLM_PRICE_IN_PER_1M")), num(env.apply("LLM_PRICE_OUT_PER_1M")),
                (int) Math.max(1, num(or(env.apply("LLM_MAX_PARALLEL"), "2"))), verifier);
    }

    /**
     * Цепочка клиентов из LLM_*, LLM_FALLBACK_*, LLM_FALLBACK2_* (BASE_URL, API_KEY, MODEL).
     * modelOverride/urlOverride/keyOverride — для проверяющей модели (подменяют только первого провайдера).
     * Все, кроме последнего, работают в режиме fastFail: не ждут перегруженный сервис.
     */
    public static LlmClient chain(Function<String, String> env, String modelOverride, String urlOverride, String keyOverride) {
        List<OpenAiCompatibleLlmClient> list = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.length; i++) {
            String p = PROVIDERS[i];
            String url = env.apply(p + "BASE_URL"), key = env.apply(p + "API_KEY"), model = env.apply(p + "MODEL");
            if (i == 0) {
                if (!blank(urlOverride)) url = urlOverride;
                if (!blank(keyOverride)) key = keyOverride;
                if (!blank(modelOverride)) model = modelOverride;
            }
            if (blank(url) || blank(key) || blank(model)) continue;
            list.add(new OpenAiCompatibleLlmClient(MAPPER, url.trim(), key.trim(), model.trim()));
        }
        if (list.isEmpty()) return new MockLlmClient(MAPPER);
        if (list.size() == 1) return list.get(0);
        for (int i = 0; i < list.size() - 1; i++) list.get(i).fastFail();
        return new CompositeLlmClient(new ArrayList<LlmClient>(list));
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
        for (String p : PROVIDERS) if (!blank(env.apply(p + "MODEL")) && !blank(env.apply(p + "API_KEY"))) parts.add(env.apply(p + "MODEL"));
        return String.join(" -> ", parts);
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String or(String a, String b) { return blank(a) ? b : a; }

    /** Пустая строка в .env -> 0, без падения приложения. */
    static double num(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return 0; }
    }
}
