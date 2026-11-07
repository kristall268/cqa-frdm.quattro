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
    private final AnswerVerifier verifier;   // null — вторая модель-проверяющий выключена

    public EvaluationRunner(LlmClient llm, PromptBuilder prompts, LlmResponseValidator validator, ScoringEngine engine,
                            double priceInPer1M, double priceOutPer1M, int maxParallel) {
        this(llm, prompts, validator, engine, priceInPer1M, priceOutPer1M, maxParallel, null);
    }

    public EvaluationRunner(LlmClient llm, PromptBuilder prompts, LlmResponseValidator validator, ScoringEngine engine,
                            double priceInPer1M, double priceOutPer1M, int maxParallel, AnswerVerifier verifier) {
        this.verifier = verifier;
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.engine = engine;
        this.priceInPer1M = priceInPer1M;
        this.priceOutPer1M = priceOutPer1M;
        this.parallel = new Semaphore(Math.max(1, maxParallel), true);
    }

    public String modelName() { return llm.modelName(); }

    private ConsensusArbiter arbiter;      // null — арбитр выключен (CQA Guard: 3-я модель по спорным критериям)
    private LlmClient localLlm;            // null — гибрид выключен; иначе звонки оцениваются локально
    private boolean maskPii = true;        // маскировать ПДн перед отправкой в облако

    public EvaluationRunner withArbiter(ConsensusArbiter a) { this.arbiter = a; return this; }
    public EvaluationRunner withLocalForCalls(LlmClient local) { this.localLlm = local; return this; }
    public EvaluationRunner withPiiMasking(boolean on) { this.maskPii = on; return this; }

    public boolean hybrid() { return localLlm != null; }

    static boolean isCall(String channel) {
        String c = channel == null ? "" : channel.toLowerCase();
        return c.contains("voice") || c.contains("call") || c.contains("звон");
    }

    public EvaluationOutcome evaluate(String conversationId, String channel, String language,
                                      List<TranscriptMessage> transcript, List<Criterion> criteria) {
        int in = 0, out = 0, calls = 0;
        boolean local = localLlm != null && isCall(channel);
        LlmClient client = local ? localLlm : llm;
        String model = client.modelName(), raw = null;
        if (transcript == null || transcript.isEmpty())
            return failed("Пустой транскрипт", model, 0, 0, 0, null);
        List<String> checklistErrors = engine.validateChecklist(criteria);
        if (!checklistErrors.isEmpty())
            return failed("Некорректный чек-лист: " + String.join("; ", checklistErrors), model, 0, 0, 0, null);
        // в облако — без персональных данных; локальной модели — как есть. Индексы реплик не меняются.
        List<TranscriptMessage> forModel = !local && maskPii ? PiiMasker.mask(transcript) : transcript;

        try {
            parallel.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("Прервано", model, 0, 0, 0, null);
        }
        try {
            List<LlmMessage> messages = new ArrayList<>(prompts.build(conversationId, channel, language, forModel, criteria));
            List<String> errors = List.of();
            for (int attempt = 1; attempt <= 2; attempt++) {
                LlmResponse r = client.complete(new LlmRequest(List.copyOf(messages), criteria, forModel));
                calls++;
                in += r.inputTokens();
                out += r.outputTokens();
                if (r.model() != null && !r.model().isBlank()) model = r.model();
                raw = r.content();
                LlmResponseValidator.Result v = validator.validate(r.content(), criteria, transcript);
                if (v.ok()) {
                    EvaluationResult result = engine.compute(criteria, v.results());
                    java.util.LinkedHashSet<String> models = new java.util.LinkedHashSet<>(List.of(model));
                    if (local) {
                        result = markLocal(result);
                    } else if (verifier != null) {
                        AnswerVerifier.Result check = verifier.verify(conversationId, channel, language, forModel, criteria, result);
                        result = AnswerVerifier.apply(result, check);
                        calls++;
                        in += check.inputTokens();
                        out += check.outputTokens();
                        if (check.model() != null) models.add(check.model());
                        if (arbiter != null && check.ok()
                                && result.criteria().stream().anyMatch(c -> Boolean.TRUE.equals(c.needsReview()))) {
                            ConsensusArbiter.Outcome a = arbiter.resolve(conversationId, channel, language, forModel,
                                    transcript, criteria, result, check);
                            calls++;
                            in += a.inputTokens();
                            out += a.outputTokens();
                            if (a.model() != null) models.add(a.model());
                            result = engine.compute(criteria, a.rows());
                        }
                    }
                    return new EvaluationOutcome(EvaluationOutcome.Status.OK, result, null, String.join(", ", models),
                            in, out, local ? 0 : cost(in, out), calls, raw);
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

    private static EvaluationResult markLocal(EvaluationResult r) {
        List<com.cqafrdm.evaluation.CriterionResult> out = new ArrayList<>();
        r.criteria().forEach(c -> out.add(c.withConsensus(null, "🔒 Локальная модель: запись звонка не покидала компьютер", null)));
        return new EvaluationResult(out, r.totalScore(), r.grossError());
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
