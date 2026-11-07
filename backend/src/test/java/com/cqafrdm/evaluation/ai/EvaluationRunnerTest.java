package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static com.cqafrdm.evaluation.TestData.MAPPER;
import static com.cqafrdm.evaluation.TestData.chat;
import static com.cqafrdm.evaluation.TestData.checklist;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {

    private final ScoringEngine engine = new ScoringEngine();

    static final String GOOD = """
            {"criteria":[
             {"criterion_id":"c1","answer":"YES","is_na":false,"reason":"Поздоровался и представился [3].","evidence_ids":[3],"checked_range":null},
             {"criterion_id":"c2","answer":null,"is_na":true,"reason":"Данных хватало.","evidence_ids":[2],"checked_range":null},
             {"criterion_id":"c3","answer":"YES","is_na":false,"reason":"Кодовое слово до раскрытия [3],[5].","evidence_ids":[3,5],"checked_range":null},
             {"criterion_id":"c4","answer":15,"is_na":false,"reason":"Срок назван, детали нет.","evidence_ids":[5],"checked_range":null},
             {"criterion_id":"c5","answer":"Да","is_na":false,"reason":"Вежлив.","evidence_ids":[5],"checked_range":null},
             {"criterion_id":"c6","answer":"10","is_na":false,"reason":"Ясно.","evidence_ids":[3,5],"checked_range":null},
             {"criterion_id":"c7","answer":"YES","is_na":false,"reason":"Попрощался [7].","evidence_ids":[7],"checked_range":null},
             {"criterion_id":"c8","answer":"NO","is_na":false,"reason":"Нарушений нет.","evidence_ids":[],"checked_range":null}
            ]}""";

    /** Фейковая модель: отдаёт заранее заданные ответы по очереди. */
    static class ScriptedLlm implements LlmClient {
        final Deque<String> answers;
        int calls;
        ScriptedLlm(String... a) { answers = new ArrayDeque<>(List.of(a)); }
        public LlmResponse complete(LlmRequest r) { calls++; return new LlmResponse(answers.poll(), 3000, 800, "fake-model"); }
        public String modelName() { return "fake-model"; }
    }

    private EvaluationRunner runner(LlmClient llm) {
        return new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), engine, 0.30, 2.50, 2);
    }

    @Test
    void validAnswerIsScored() {
        EvaluationOutcome o = runner(new ScriptedLlm("```json\n" + GOOD + "\n```")).evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
        // c2 N/A: веса 85; баллы 10+20+15+15+15+5 = 80 -> 94.12
        assertEquals(94.12, o.totalScore().doubleValue());
        assertEquals("YES", o.result().criteria().get(4).answer()); // "Да" нормализовано
        assertEquals("15", o.result().criteria().get(3).answer());  // число 15 нормализовано
        assertEquals(1, o.llmCalls());
        assertEquals(0.0029, o.costUsd(), 1e-9); // 3000*0.3/1e6 + 800*2.5/1e6
    }

    @Test
    void invalidThenRepairedAnswerUsesRetryAndSumsCost() {
        String bad = GOOD.replace("\"evidence_ids\":[7]", "\"evidence_ids\":[42]");
        ScriptedLlm llm = new ScriptedLlm(bad, GOOD);
        EvaluationOutcome o = runner(llm).evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status());
        assertEquals(2, llm.calls);
        assertEquals(6000, o.inputTokens());
        assertEquals(0.0058, o.costUsd(), 1e-9);
    }

    @Test
    void twiceInvalidGivesFailedWithoutFakeScore() {
        String bad = GOOD.replace("{\"criterion_id\":\"c8\",\"answer\":\"NO\",\"is_na\":false,\"reason\":\"Нарушений нет.\",\"evidence_ids\":[],\"checked_range\":null}", "")
                .replace("[7],\"checked_range\":null},", "[7],\"checked_range\":null}");
        EvaluationOutcome o = runner(new ScriptedLlm(bad, bad)).evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.FAILED, o.status());
        assertNull(o.result());
        assertNull(o.totalScore());
        assertTrue(o.error().contains("c8"), o.error());
    }

    @Test
    void notJsonGivesFailed() {
        EvaluationOutcome o = runner(new ScriptedLlm("Sorry, I cannot", "still no")).evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.FAILED, o.status());
    }

    @Test
    void positiveAnswerMustCiteOperatorMessage() {
        // c1 YES, но доказательство — приветствие бота [1]: нельзя путать бота с оператором
        String botEvidence = GOOD.replace("\"reason\":\"Поздоровался и представился [3].\",\"evidence_ids\":[3]",
                "\"reason\":\"Поздоровался.\",\"evidence_ids\":[1]");
        var v = new LlmResponseValidator(MAPPER, engine).validate(botEvidence, checklist(), chat());
        assertFalse(v.ok());
        assertTrue(v.errors().get(0).startsWith("c1"), v.errors().toString());
    }

    @Test
    void naNotAllowedIsRejected() {
        String naC1 = GOOD.replace("{\"criterion_id\":\"c1\",\"answer\":\"YES\",\"is_na\":false",
                "{\"criterion_id\":\"c1\",\"answer\":null,\"is_na\":true");
        var v = new LlmResponseValidator(MAPPER, engine).validate(naC1, checklist(), chat());
        assertFalse(v.ok());
    }

    @Test
    void missingActionNeedsEvidenceOrRange() {
        String noWithout = GOOD.replace("{\"criterion_id\":\"c7\",\"answer\":\"YES\",\"is_na\":false,\"reason\":\"Попрощался [7].\",\"evidence_ids\":[7]",
                "{\"criterion_id\":\"c7\",\"answer\":\"NO\",\"is_na\":false,\"reason\":\"Не попрощался.\",\"evidence_ids\":[]");
        assertFalse(new LlmResponseValidator(MAPPER, engine).validate(noWithout, checklist(), chat()).ok());
        String noWithRange = noWithout.replace("\"evidence_ids\":[],\"checked_range\":null}\n            ]",
                "\"evidence_ids\":[],\"checked_range\":null}\n            ]")
                .replace("\"reason\":\"Не попрощался.\",\"evidence_ids\":[],\"checked_range\":null",
                        "\"reason\":\"Не попрощался.\",\"evidence_ids\":[],\"checked_range\":{\"from\":5,\"to\":8}");
        var v = new LlmResponseValidator(MAPPER, engine).validate(noWithRange, checklist(), chat());
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(5, v.results().get(6).checkedRange().from());
    }

    @Test
    void mockClientProducesValidEvaluation() {
        EvaluationOutcome o = runner(new MockLlmClient(MAPPER)).evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
        assertEquals(100.0, o.totalScore().doubleValue());
    }

    @Test
    void newCriterionFromSettingsIsUsedWithoutCodeChanges() {
        List<Criterion> changed = new java.util.ArrayList<>(checklist());
        changed.set(4, new Criterion("c5b", "Персонализация", "Ведение", "Да — оператор обратился к клиенту по имени.",
                com.cqafrdm.checklist.CriterionType.BINARY, BigDecimal.valueOf(15), false, null, null));
        String prompt = new PromptBuilder().userMessage("p1", "agent chat", "RU", chat(), changed);
        assertTrue(prompt.contains("c5b") && prompt.contains("Персонализация"));
        EvaluationOutcome o = runner(new MockLlmClient(MAPPER)).evaluate("p1", "agent chat", "RU", chat(), changed);
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
        assertEquals("c5b", o.result().criteria().get(4).criterionId());
    }

    @Test
    void promptNumbersMessagesAndKeepsTimestamps() {
        List<TranscriptMessage> t = chat();
        String p = new PromptBuilder().userMessage("p1", "agent chat", "RU", t, checklist());
        assertTrue(p.contains("[3] (00:40) OPERATOR: Здравствуйте! Меня зовут Айгерим."));
        assertTrue(p.contains("[1] (00:02) BOT:"));
    }

    static String verdicts(String c3) {
        StringBuilder sb = new StringBuilder("{\"verdicts\":[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) sb.append(',');
            sb.append(i == 3 && c3 != null ? c3
                    : "{\"criterion_id\":\"c" + i + "\",\"agree\":true,\"comment\":\"ок\"}");
        }
        return sb.append("]}").toString();
    }

    private EvaluationRunner runnerWithVerifier(LlmClient llm, LlmClient verifierLlm) {
        return new EvaluationRunner(llm, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), engine, 0.30, 2.50, 2,
                new AnswerVerifier(verifierLlm, new PromptBuilder(), MAPPER));
    }

    @Test
    void verifierAgreementConfirmsAllAndKeepsScore() {
        EvaluationOutcome o = runnerWithVerifier(new ScriptedLlm(GOOD), new ScriptedLlm(verdicts(null)))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
        assertEquals(94.12, o.totalScore().doubleValue());
        assertTrue(o.result().criteria().stream().allMatch(c -> Boolean.FALSE.equals(c.needsReview())));
        assertEquals(2, o.llmCalls());
        assertEquals(6000, o.inputTokens());
        assertTrue(o.model().contains("fake-model"));
    }

    @Test
    void verifierDisagreementFlagsOnlyThatCriterion() {
        String c3 = "{\"criterion_id\":\"c3\",\"agree\":false,\"suggested_answer\":\"NO\",\"suggested_is_na\":false,"
                + "\"comment\":\"Кодовое слово проверено после раскрытия [5].\"}";
        EvaluationOutcome o = runnerWithVerifier(new ScriptedLlm(GOOD), new ScriptedLlm(verdicts(c3)))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(94.12, o.totalScore().doubleValue());                  // баллы не меняются
        var c = o.result().criteria().get(2);
        assertEquals(Boolean.TRUE, c.needsReview());
        assertTrue(c.verification().contains("NO"), c.verification());
        assertEquals(1L, o.result().criteria().stream().filter(x -> Boolean.TRUE.equals(x.needsReview())).count());
    }

    @Test
    void verifierFailureDoesNotBreakEvaluation() {
        EvaluationOutcome o = runnerWithVerifier(new ScriptedLlm(GOOD), new ScriptedLlm("not json"))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status());
        assertNull(o.result().criteria().get(0).needsReview());
        assertTrue(o.result().criteria().get(0).verification().contains("недоступна"));
    }

    @Test
    void appealDecisionClearsReviewFlag() {
        String c3 = "{\"criterion_id\":\"c3\",\"agree\":false,\"suggested_answer\":\"NO\",\"comment\":\"x\"}";
        EvaluationOutcome o = runnerWithVerifier(new ScriptedLlm(GOOD), new ScriptedLlm(verdicts(c3)))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        var fin = engine.applyOverride(checklist(), o.result(), "c3", "NO", false, "Согласен со второй моделью");
        assertEquals(Boolean.FALSE, fin.criteria().get(2).needsReview());
        assertEquals(Boolean.FALSE, fin.criteria().get(0).needsReview());  // остальные сохранены
        assertEquals(70.59, fin.totalScore().doubleValue());               // 60 / 85
    }
}
