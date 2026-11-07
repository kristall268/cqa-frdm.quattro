package com.cqafrdm.evaluation.ai;

import com.cqafrdm.evaluation.model.EvaluationResult;

/**
 * Что сохранить в таблицу evaluation.
 * status OK -> result заполнен; FAILED -> result == null, error объясняет причину (фиктивной оценки нет).
 * costUsd — сумма по ВСЕМ вызовам модели (включая повторный).
 */
public record EvaluationOutcome(
        Status status,
        EvaluationResult result,
        String error,
        String model,
        int inputTokens,
        int outputTokens,
        double costUsd,
        int llmCalls,
        String rawResponse) {

    public enum Status { OK, FAILED }

    public Double totalScore() { return result == null ? null : result.totalScore(); }

    public Boolean grossError() { return result == null ? null : result.grossError(); }
}
