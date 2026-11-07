package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.EvaluationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Вторая модель-проверяющий: независимо перепроверяет каждый критерий первой оценки.
 * Баллы НЕ меняет. Где не согласна — needsReview=true и комментарий, что и почему предлагает.
 * Если проверка не удалась — оценка остаётся OK, needsReview=null («проверка недоступна»).
 */
public class AnswerVerifier {

    static final String SYSTEM = """
            You are a SENIOR quality-assurance reviewer at a brokerage contact center (Freedom Broker).
            Another auditor has already evaluated a human OPERATOR in one conversation against a checklist.
            Your job: independently re-check EVERY criterion against the checklist rules and the transcript, and say whether
            the auditor's answer is correct.

            Apply the same rules as the auditor:
            - only operator messages are evaluated; bot and system messages are context only (a bot greeting is not the operator's);
            - NO = a required action was skipped or done wrong; N/A = only when na_allowed=true AND its stated condition is met;
            - greeting = in the client's language AND self-introduction in the FIRST operator reply;
            - identification is required before protected data/actions; general questions -> N/A;
            - gross errors are ONLY: insulting the client, offering a paid service bypassing the company
              (personal money transfer for investing, or the client's personal number for a private paid consultation —
              the offer alone is enough), disclosing confidential information;
            - check that the cited evidence messages really support the auditor's reason.

            agree=true  — the auditor's answer is correct or clearly defensible.
            agree=false — the answer is wrong; give the answer you believe is correct.
            Do not disagree over wording of the reason; disagree only about the verdict itself.

            Return ONLY JSON:
            {"verdicts":[{"criterion_id":"c1","agree":true,"suggested_answer":null,"suggested_is_na":false,"comment":"..."}]}
            - one item for EVERY criterion;
            - when agree=false: suggested_answer = "YES"|"NO"|scale value as string, or null with suggested_is_na=true;
            - comment: one short sentence IN RUSSIAN with the decisive fact and message index, e.g. "Оператор не представился в [3].".
            """;

    public record Verdict(boolean agree, String suggestedAnswer, boolean suggestedIsNa, String comment) {}

    public record Result(Map<String, Verdict> verdicts, int inputTokens, int outputTokens, String model, String error) {
        boolean ok() { return error == null; }
    }

    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ObjectMapper mapper;

    public AnswerVerifier(LlmClient llm, PromptBuilder prompts, ObjectMapper mapper) {
        this.llm = llm;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    public String modelName() { return llm.modelName(); }

    public Result verify(String conversationId, String channel, String language,
                         List<TranscriptMessage> transcript, List<Criterion> criteria, EvaluationResult evaluation) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", SYSTEM));
        messages.add(new LlmMessage("user", prompts.userMessage(conversationId, channel, language, transcript, criteria)
                .replace("Evaluate the operator now. Return the JSON object only.", "")
                + "\nAUDITOR'S EVALUATION\n" + auditorJson(evaluation)
                + "\n\nRe-check every criterion now. Return the JSON object only."));
        LlmResponse r;
        try {
            r = llm.complete(new LlmRequest(messages, criteria, transcript));
        } catch (LlmException e) {
            return new Result(Map.of(), 0, 0, llm.modelName(), e.getMessage());
        }
        try {
            JsonNode root = mapper.readTree(LlmResponseValidator.stripFences(r.content()));
            JsonNode arr = root.isArray() ? root : root.path("verdicts");
            Map<String, Verdict> out = new HashMap<>();
            for (JsonNode v : arr) {
                String id = v.path("criterion_id").asText(null);
                if (id == null || !v.has("agree")) continue;
                JsonNode sa = v.get("suggested_answer");
                String suggested = sa == null || sa.isNull() ? null
                        : sa.isNumber() ? String.valueOf(sa.asInt()) : sa.asText().trim().toUpperCase();
                out.put(id, new Verdict(v.path("agree").asBoolean(true), suggested,
                        v.path("suggested_is_na").asBoolean(false), v.path("comment").asText("")));
            }
            if (out.isEmpty()) throw new IllegalStateException("нет verdicts");
            return new Result(out, r.inputTokens(), r.outputTokens(), r.model(), null);
        } catch (Exception e) {
            return new Result(Map.of(), r.inputTokens(), r.outputTokens(), r.model(), "ответ проверки не разобран: " + e.getMessage());
        }
    }

    /** Применяет вердикты: баллы не меняются, только needsReview/verification. */
    public static EvaluationResult apply(EvaluationResult e, Result v) {
        List<CriterionResult> out = new ArrayList<>();
        for (CriterionResult c : e.criteria()) {
            if (!v.ok()) {
                out.add(c.withVerification(null, "Проверка второй моделью недоступна: " + v.error()));
                continue;
            }
            Verdict d = v.verdicts().get(c.criterionId());
            if (d == null) {
                out.add(c.withVerification(null, "Вторая модель не дала вердикт"));
            } else if (d.agree() || sameAsCurrent(c, d)) {
                out.add(c.withConsensus(false, "✓ Подтверждено второй моделью" + suffix(d.comment()), 100));
            } else {
                String proposal = d.suggestedIsNa() ? "N/A" : String.valueOf(d.suggestedAnswer());
                out.add(c.withConsensus(true, "⚠ Вторая модель не согласна, предлагает «" + proposal + "»" + suffix(d.comment()), 50));
            }
        }
        return new EvaluationResult(out, e.totalScore(), e.grossError());
    }

    private static boolean sameAsCurrent(CriterionResult c, Verdict d) {
        if (d.suggestedIsNa()) return c.isNa();
        return !c.isNa() && d.suggestedAnswer() != null && d.suggestedAnswer().equals(c.answer());
    }

    private static String suffix(String comment) {
        return comment == null || comment.isBlank() ? "" : ": " + comment.trim();
    }

    private String auditorJson(EvaluationResult e) {
        ArrayNode arr = mapper.createArrayNode();
        for (CriterionResult c : e.criteria()) {
            ObjectNode o = arr.addObject();
            o.put("criterion_id", c.criterionId());
            if (c.isNa()) o.putNull("answer"); else o.put("answer", c.answer());
            o.put("is_na", c.isNa());
            o.put("reason", c.reason());
            ArrayNode ev = o.putArray("evidence_ids");
            if (c.evidenceIds() != null) c.evidenceIds().forEach(ev::add);
            if (c.checkedRange() != null) o.putObject("checked_range").put("from", c.checkedRange().from()).put("to", c.checkedRange().to());
        }
        return arr.toString();
    }
}
