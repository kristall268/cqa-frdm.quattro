package com.cqafrdm.evaluation.ai;

import com.cqafrdm.evaluation.scoring.ScoringEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public LlmClient llmClient(
            @Value("${cqa.llm.base-url:${LLM_BASE_URL:}}") String baseUrl,
            @Value("${cqa.llm.api-key:${LLM_API_KEY:}}") String apiKey,
            @Value("${cqa.llm.model:${LLM_MODEL:}}") String model) {
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            log.warn("LLM_API_KEY/LLM_BASE_URL не заданы — используется MockLlmClient (тестовые оценки)");
            return new MockLlmClient(MAPPER);
        }
        String m = model == null || model.isBlank() ? "gemini-3.8-flash" : model.trim();
        log.info("LLM: {} via {}", m, baseUrl);
        return new OpenAiCompatibleLlmClient(MAPPER, baseUrl.trim(), apiKey.trim(), m);
    }

    @Bean
    public EvaluationRunner evaluationRunner(
            LlmClient llmClient, ScoringEngine scoringEngine,
            @Value("${cqa.llm.price-in-per-1m:${LLM_PRICE_IN_PER_1M:0}}") String priceIn,
            @Value("${cqa.llm.price-out-per-1m:${LLM_PRICE_OUT_PER_1M:0}}") String priceOut,
            @Value("${cqa.llm.max-parallel:${LLM_MAX_PARALLEL:2}}") String maxParallel) {
        return new EvaluationRunner(llmClient, new PromptBuilder(), new LlmResponseValidator(MAPPER, scoringEngine),
                scoringEngine, num(priceIn), num(priceOut), (int) Math.max(1, num(maxParallel)));
    }

    /** Пустая строка в .env -> 0, без падения приложения. */
    static double num(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return 0; }
    }
}
