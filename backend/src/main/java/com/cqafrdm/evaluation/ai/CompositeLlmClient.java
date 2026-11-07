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

    /** Сколько раз пройти всю цепочку заново, если ВСЕ провайдеры упёрлись в минутный лимит (429/503). */
    static int ROUNDS = 4;
    static long WAIT_MS = 20_000;

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<String> errors = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            errors.clear();
            for (LlmClient c : chain) {
                try {
                    return c.complete(request);
                } catch (LlmException e) {
                    errors.add(e.getMessage());
                }
            }
            boolean onlyLimits = errors.stream().allMatch(m -> m != null && (m.contains("429") || m.contains("503")
                    || m.toLowerCase().contains("rate limit") || m.toLowerCase().contains("high demand")));
            if (!onlyLimits || round == ROUNDS) break;
            try {                                   // минутный лимит (TPM) обновится — пробуем цепочку снова
                Thread.sleep(WAIT_MS * round);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new LlmException(String.join(" || ", errors));
    }
}
