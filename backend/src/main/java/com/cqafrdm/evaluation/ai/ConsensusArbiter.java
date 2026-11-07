package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.EvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CQA Guard — третья модель-арбитр. Вызывается ТОЛЬКО по спорным критериям (оценщик и проверяющий
 * не согласны) и выносит независимое решение. Итог — большинство голосов из трёх:
 * <pre>
 *   все согласны                         -> confidence 100
 *   арбитр поддержал одну из сторон      -> confidence 67 (2 из 3), ответ большинства
 *   арбитр дал третий ответ / недоступен -> confidence 34, остаётся ответ оценщика, needsReview = true
 * </pre>
 * Баллы после решения пересчитывает ScoringEngine (вызывающий код).
 */
public class ConsensusArbiter {

    static final String TASK = """

            ARBITRATION TASK
            Two independent auditors evaluated this conversation and DISAGREE on the criteria listed below.
            Re-read the transcript and the rules and give YOUR OWN independent verdict for ONLY these criteria.
            Do not prefer an auditor because of wording; decide strictly by the checklist rules and the messages.
            Use the same JSON output format, with one item for each listed criterion only.
            """;

    public record Outcome(List<CriterionResult> rows, int inputTokens, int outputTokens, String model, String error) {}

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final LlmResponseValidator validator;
    private final ObjectMapper mapper;

    public ConsensusArbiter(LlmClient llm, PromptBuilder prompts, LlmResponseValidator validator, ObjectMapper mapper) {
        this.llm = llm;
        this.prompts = prompts;
        this.validator = validator;
        this.mapper = mapper;
    }

    public String modelName() { return llm.modelName(); }

    /**
     * @param result  оценка после проверки (needsReview=true у спорных)
     * @param check   вердикты проверяющей модели
     * @param transcriptForModel транскрипт, который можно отправить этой модели (маскированный для облака)
     * @param transcript исходный транскрипт (для проверки индексов)
     */
    public Outcome resolve(String conversationId, String channel, String language,
                           List<TranscriptMessage> transcriptForModel, List<TranscriptMessage> transcript,
                           List<Criterion> criteria, EvaluationResult result, AnswerVerifier.Result check) {
        List<String> disputed = new ArrayList<>();
        for (CriterionResult c : result.criteria()) if (Boolean.TRUE.equals(c.needsReview())) disputed.add(c.criterionId());
        if (disputed.isEmpty() || !check.ok()) return new Outcome(result.criteria(), 0, 0, llm.modelName(), null);

        List<Criterion> subset = criteria.stream().filter(c -> disputed.contains(c.id())).toList();
        StringBuilder opinions = new StringBuilder("\nAUDITOR OPINIONS ON DISPUTED CRITERIA\n");
        for (CriterionResult c : result.criteria()) {
            if (!disputed.contains(c.criterionId())) continue;
            AnswerVerifier.Verdict v = check.verdicts().get(c.criterionId());
            opinions.append("- ").append(c.criterionId())
                    .append(": auditor A = ").append(c.isNa() ? "N/A" : c.answer()).append(" (").append(nz(c.reason())).append(")")
                    .append("; auditor B = ").append(v == null ? "?" : v.suggestedIsNa() ? "N/A" : v.suggestedAnswer())
                    .append(" (").append(v == null ? "" : nz(v.comment())).append(")\n");
        }
        List<LlmMessage> messages = new ArrayList<>(prompts.build(conversationId, channel, language, transcriptForModel, subset));
        LlmMessage user = messages.remove(messages.size() - 1);
        messages.add(new LlmMessage("user", user.content() + TASK + opinions));

        LlmResponse r;
        try {
            r = llm.complete(new LlmRequest(messages, subset, transcriptForModel));
        } catch (LlmException e) {
            return new Outcome(markUnresolved(result.criteria(), disputed, "арбитр недоступен"), 0, 0, llm.modelName(), e.getMessage());
        }
        LlmResponseValidator.Result v = validator.validate(r.content(), subset, transcript);
        if (!v.ok()) {
            return new Outcome(markUnresolved(result.criteria(), disputed, "ответ арбитра некорректен"),
                    r.inputTokens(), r.outputTokens(), r.model(), String.join("; ", v.errors()));
        }
        Map<String, CriterionResult> arb = new java.util.HashMap<>();
        v.results().forEach(x -> arb.put(x.criterionId(), x));

        List<CriterionResult> rows = new ArrayList<>();
        for (CriterionResult c : result.criteria()) {
            if (!disputed.contains(c.criterionId())) { rows.add(c); continue; }
            CriterionResult a = arb.get(c.criterionId());
            AnswerVerifier.Verdict b = check.verdicts().get(c.criterionId());
            if (a == null) { rows.add(c.withConsensus(true, c.verification() + " · арбитр не ответил", 34)); continue; }
            if (same(a.isNa(), a.answer(), c.isNa(), c.answer())) {
                rows.add(c.withConsensus(false, "⚖ 2 из 3: арбитр подтвердил оценку. Вторая модель предлагала «"
                        + label(b) + "»", 67));
            } else if (b != null && same(a.isNa(), a.answer(), b.suggestedIsNa(), b.suggestedAnswer())) {
                rows.add(new CriterionResult(c.criterionId(), c.title(), c.type(), c.weight(), a.answer(), a.isNa(), null,
                        a.reason(), a.evidenceIds(), a.checkedRange(), false,
                        "⚖ 2 из 3: арбитр согласился со второй моделью. Первая оценка была «"
                                + (c.isNa() ? "N/A" : c.answer()) + "»", 67));
            } else {
                rows.add(c.withConsensus(true, "⚠ Три модели дали разные ответы (A «" + (c.isNa() ? "N/A" : c.answer())
                        + "», B «" + label(b) + "», арбитр «" + (a.isNa() ? "N/A" : a.answer()) + "»): нужна проверка человеком", 34));
            }
        }
        return new Outcome(rows, r.inputTokens(), r.outputTokens(), r.model(), null);
    }

    private static List<CriterionResult> markUnresolved(List<CriterionResult> in, List<String> disputed, String why) {
        List<CriterionResult> out = new ArrayList<>();
        for (CriterionResult c : in)
            out.add(disputed.contains(c.criterionId()) ? c.withConsensus(true, c.verification() + " · " + why, 50) : c);
        return out;
    }

    private static boolean same(boolean naA, String a, boolean naB, String b) {
        return naA ? naB : !naB && Objects.equals(a, b);
    }

    private static String label(AnswerVerifier.Verdict b) {
        return b == null ? "?" : b.suggestedIsNa() ? "N/A" : String.valueOf(b.suggestedAnswer());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
