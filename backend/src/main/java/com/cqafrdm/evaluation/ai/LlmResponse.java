package com.cqafrdm.evaluation.ai;

/** Ответ модели + расход токенов для расчёта стоимости. */
public record LlmResponse(String content, int inputTokens, int outputTokens, String model) {}
