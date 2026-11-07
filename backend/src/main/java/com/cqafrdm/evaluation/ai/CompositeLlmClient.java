package com.cqafrdm.evaluation.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Цепочка провайдеров: если первый (например, Groq) не ответил или упёрся в лимит —
 * тот же запрос уходит следующему (Gemini, OpenRouter, DeepSeek...). Оценка не падает из-за одного сервиса.
 */
public class CompositeLlmClient implements LlmClient {

    private final List<LlmClient> chain;

    public CompositeLlmClient(List<LlmClient> chain) {
        if (chain.isEmpty()) throw new IllegalArgumentException("пустая цепочка LLM");
        this.chain = List.copyOf(chain);
    }

    public CompositeLlmClient(LlmClient primary, LlmClient fallback) {
        this(List.of(primary, fallback));
    }

    @Override
    public String modelName() { return chain.get(0).modelName(); }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<String> errors = new ArrayList<>();
        for (LlmClient c : chain) {
            try {
                return c.complete(request);
            } catch (LlmException e) {
                errors.add(e.getMessage());
            }
        }
        throw new LlmException(String.join(" || ", errors));
    }
}
