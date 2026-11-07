package com.cqafrdm.evaluation.ai;

/** Сменяемый адаптер модели. Бизнес-логика не знает, какой провайдер за ним стоит. */
public interface LlmClient {
    LlmResponse complete(LlmRequest request) throws LlmException;

    /** Имя модели для отображения в UI ("gemini-3.8-flash", "mock"). */
    String modelName();
}
