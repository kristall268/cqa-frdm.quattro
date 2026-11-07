package com.cqafrdm.evaluation.ai;

/**
 * Резерв на уровне провайдера: если основной (например, бесплатный Gemini) не ответил —
 * тот же запрос уходит резервному (например, Groq). Для демо: оценка не падает из-за перегрузки одного сервиса.
 */
public class CompositeLlmClient implements LlmClient {

    private final LlmClient primary;
    private final LlmClient fallback;

    public CompositeLlmClient(LlmClient primary, LlmClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String modelName() { return primary.modelName(); }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        try {
            return primary.complete(request);
        } catch (LlmException first) {
            try {
                return fallback.complete(request);
            } catch (LlmException second) {
                throw new LlmException(first.getMessage() + " || резерв: " + second.getMessage());
            }
        }
    }
}
