package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.scoring.ScoringEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Точка входа для EvaluationService (зона №1):
 * <pre>
 *   EvaluationOutcome o = runner.evaluate(conv.id, conv.channel, conv.language, transcript, checklistVersion.criteria);
 *   // сохранить o.status(), o.result(), o.totalScore(), o.grossError(), o.model(), токены, o.costUsd(), o.error()
 * </pre>
 * Поток: промпт -> модель -> валидация -> (1 повтор с ошибками) -> ScoringEngine.
 * Баллы считает только ScoringEngine, модель выдаёт лишь ответы и доказательства.
 */
public class EvaluationRunner {

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final LlmResponseValidator validator;
    private final ScoringEngine engine;
    private final double priceInPer1M;
    private final double priceOutPer1M;
    private final Semaphore parallel;

    public EvaluationRunner(LlmClient llm, PromptBuilder prompts, LlmResponseValidator validator, ScoringEngine engine,
                            double priceInPer1M, double priceOutPer1M, int maxParallel) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.engine = engine;
        this.priceInPer1M = priceInPer1M;
        this.priceOutPer1M = priceOutPer1M;
        this.parallel = new Semaphore(Math.max(1, maxParallel), true);
    }

    public String modelName() { return llm.modelName(); }

    public EvaluationOutcome evaluate(String conversationId, String channel, String language,
                                      List<TranscriptMessage> transcript, List<Criterion> criteria) {
        int in = 0, out = 0, calls = 0;
        String model = llm.modelName(), raw = null;
        if (transcript == null || transcript.isEmpty())
            return failed("Пустой транскрипт", model, 0, 0, 0, null);
        List<String> checklistErrors = engine.validateChecklist(criteria);
        if (!checklistErrors.isEmpty())
            return failed("Некорректный чек-лист: " + String.join("; ", checklistErrors), model, 0, 0, 0, null);

        try {
            parallel.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("Прервано", model, 0, 0, 0, null);
        }
        try {
            List<LlmMessage> messages = new ArrayList<>(prompts.build(conversationId, channel, language, transcript, criteria));
            List<String> errors = List.of();
            for (int attempt = 1; attempt <= 2; attempt++) {
                LlmResponse r = llm.complete(new LlmRequest(List.copyOf(messages), criteria, transcript));
                calls++;
                in += r.inputTokens();
                out += r.outputTokens();
                if (r.model() != null && !r.model().isBlank()) model = r.model();
                raw = r.content();
                LlmResponseValidator.Result v = validator.validate(r.content(), criteria, transcript);
                if (v.ok()) {
                    EvaluationResult result = engine.compute(criteria, v.results());
                    return new EvaluationOutcome(EvaluationOutcome.Status.OK, result, null, model, in, out,
                            cost(in, out), calls, raw);
                }
                errors = v.errors();
                messages.add(new LlmMessage("assistant", r.content()));
                messages.add(prompts.repairMessage(errors));
            }
            return failed("Ответ модели не прошёл проверку: " + String.join("; ", errors), model, in, out, calls, raw);
        } catch (LlmException e) {
            return failed("Ошибка модели: " + e.getMessage(), model, in, out, calls, raw);
        } catch (RuntimeException e) {
            return failed("Внутренняя ошибка оценки: " + e.getMessage(), model, in, out, calls, raw);
        } finally {
            parallel.release();
        }
    }

    /** Стоимость всех вызовов в USD (по ценам из .env). */
    public double cost(int inputTokens, int outputTokens) {
        double c = inputTokens * priceInPer1M / 1_000_000d + outputTokens * priceOutPer1M / 1_000_000d;
        return BigDecimal.valueOf(c).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private EvaluationOutcome failed(String error, String model, int in, int out, int calls, String raw) {
        return new EvaluationOutcome(EvaluationOutcome.Status.FAILED, null, error, model, in, out, cost(in, out), calls, raw);
    }
}
