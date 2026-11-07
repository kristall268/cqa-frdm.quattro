package com.cqafrdm.evaluation.ai;

import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.cqafrdm.evaluation.TestData.MAPPER;
import static com.cqafrdm.evaluation.TestData.chat;
import static com.cqafrdm.evaluation.TestData.checklist;
import static com.cqafrdm.evaluation.ai.EvaluationRunnerTest.GOOD;
import static com.cqafrdm.evaluation.ai.EvaluationRunnerTest.verdicts;
import static org.junit.jupiter.api.Assertions.*;

/** CQA Guard: арбитр, уверенность, гибрид, маскирование ПДн. */
class GuardTest {

    private final ScoringEngine engine = new ScoringEngine();

    static final String C3_DISPUTE = "{\"criterion_id\":\"c3\",\"agree\":false,\"suggested_answer\":\"NO\",\"suggested_is_na\":false,\"comment\":\"x\"}";

    static String arbiter(String answer) {
        return "{\"criteria\":[{\"criterion_id\":\"c3\",\"answer\":\"" + answer + "\",\"is_na\":false,"
                + "\"reason\":\"Арбитр: проверка по кодовому слову [3].\",\"evidence_ids\":[3,5],\"checked_range\":{\"from\":3,\"to\":5}}]}";
    }

    /** Модель, которая запоминает, какой транскрипт ей прислали. */
    static class Spy extends EvaluationRunnerTest.ScriptedLlm {
        List<String> prompts = new ArrayList<>();
        Spy(String... a) { super(a); }
        @Override public LlmResponse complete(LlmRequest r) {
            prompts.add(r.messages().get(r.messages().size() - 1).content());
            return super.complete(r);
        }
    }

    private EvaluationRunner runner(LlmClient eval, LlmClient verify, LlmClient arb) {
        EvaluationRunner r = new EvaluationRunner(eval, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), engine,
                0.3, 2.5, 2, new AnswerVerifier(verify, new PromptBuilder(), MAPPER));
        if (arb != null) r.withArbiter(new ConsensusArbiter(arb, new PromptBuilder(), new LlmResponseValidator(MAPPER, engine), MAPPER));
        return r;
    }

    @Test
    void allAgreeGivesFullConfidence() {
        var o = runner(new EvaluationRunnerTest.ScriptedLlm(GOOD), new EvaluationRunnerTest.ScriptedLlm(verdicts(null)), null)
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertTrue(o.result().criteria().stream().allMatch(c -> Integer.valueOf(100).equals(c.confidence())));
    }

    @Test
    void arbiterSidesWithEvaluator() {
        Spy arb = new Spy(arbiter("YES"));
        var o = runner(new EvaluationRunnerTest.ScriptedLlm(GOOD), new EvaluationRunnerTest.ScriptedLlm(verdicts(C3_DISPUTE)), arb)
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        CriterionResult c3 = o.result().criteria().get(2);
        assertEquals("YES", c3.answer());
        assertEquals(67, c3.confidence());
        assertEquals(Boolean.FALSE, c3.needsReview());
        assertEquals(94.12, o.totalScore().doubleValue());
        assertEquals(3, o.llmCalls());
        assertTrue(arb.prompts.get(0).contains("ARBITRATION TASK") && arb.prompts.get(0).contains("c3"));
        assertFalse(arb.prompts.get(0).contains("criterion_id: c1"), "арбитр получает только спорные критерии");
    }

    @Test
    void arbiterSidesWithVerifierChangesAnswerAndScore() {
        var o = runner(new EvaluationRunnerTest.ScriptedLlm(GOOD), new EvaluationRunnerTest.ScriptedLlm(verdicts(C3_DISPUTE)),
                new EvaluationRunnerTest.ScriptedLlm(arbiter("NO")))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        CriterionResult c3 = o.result().criteria().get(2);
        assertEquals("NO", c3.answer());
        assertEquals(0.0, c3.points().doubleValue());
        assertEquals(67, c3.confidence());
        assertTrue(c3.verification().contains("второй моделью"));
        assertEquals(70.59, o.totalScore().doubleValue());   // 60 / 85
    }

    @Test
    void arbiterFailureKeepsEvaluationAndFlagsHuman() {
        var o = runner(new EvaluationRunnerTest.ScriptedLlm(GOOD), new EvaluationRunnerTest.ScriptedLlm(verdicts(C3_DISPUTE)),
                new EvaluationRunnerTest.ScriptedLlm("garbage"))
                .evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status());
        assertEquals(Boolean.TRUE, o.result().criteria().get(2).needsReview());
        assertEquals("YES", o.result().criteria().get(2).answer());
    }

    @Test
    void cloudGetsMaskedPiiButEvidenceIndicesStay() {
        List<TranscriptMessage> t = new ArrayList<>(chat());
        t.set(4, new TranscriptMessage("01:10", "client", "ИИН 990101300123, карта 4400 4301 2345 6789, тел +7 701 123 45 67"));
        Spy eval = new Spy(GOOD);
        var o = runner(eval, new EvaluationRunnerTest.ScriptedLlm(verdicts(null)), null).evaluate("p1", "agent chat", "RU", t, checklist());
        String sent = eval.prompts.get(0);
        assertFalse(sent.contains("990101300123") || sent.contains("4400 4301") || sent.contains("701 123"), sent);
        assertTrue(sent.contains("[ИИН]") && sent.contains("[КАРТА]") && sent.contains("[ТЕЛЕФОН]"));
        assertEquals(EvaluationOutcome.Status.OK, o.status());
    }

    @Test
    void hybridSendsCallsToLocalModelWithoutMaskingAndWithoutCloud() {
        Spy cloud = new Spy(GOOD), local = new Spy(GOOD);
        List<TranscriptMessage> t = new ArrayList<>(chat());
        t.set(4, new TranscriptMessage("01:10", "client", "ИИН 990101300123"));
        EvaluationRunner r = runner(cloud, new EvaluationRunnerTest.ScriptedLlm(verdicts(null)), null).withLocalForCalls(local);
        var o = r.evaluate("call-1", "voice call", "RU", t, checklist());
        assertEquals(EvaluationOutcome.Status.OK, o.status(), o.error());
        assertTrue(cloud.prompts.isEmpty(), "звонок не должен уйти в облако");
        assertTrue(local.prompts.get(0).contains("990101300123"));
        assertEquals(0.0, o.costUsd());
        assertTrue(o.result().criteria().get(0).verification().contains("Локальная"));
        var chatOutcome = r.evaluate("p1", "agent chat", "RU", chat(), checklist());
        assertEquals(1, cloud.prompts.size(), "чат идёт в облако");
        assertEquals(EvaluationOutcome.Status.OK, chatOutcome.status());
    }

    @Test
    void maskerKeepsOrdinaryNumbers() {
        assertEquals("Комиссия 0.1% и $1.2, заявка 12345", PiiMasker.mask("Комиссия 0.1% и $1.2, заявка 12345"));
        assertEquals("почта [EMAIL]", PiiMasker.mask("почта a.b@mail.kz"));
        assertEquals("[IBAN]", PiiMasker.mask("KZ86125KZT5004100100"));
    }

    @Test
    void longCallPromptUsesShortTimesAndKeepsIndices() {
        List<TranscriptMessage> t = new ArrayList<>();
        for (int i = 0; i < 70; i++)
            t.add(new TranscriptMessage("2026-05-12T10:0" + (i / 60) + ":" + String.format("%02d", i % 60) + "+05:00",
                    i % 2 == 0 ? "client" : "operator", "x".repeat(i == 5 ? 900 : 20)));
        String p = new PromptBuilder().userMessage("call", "voice call", "RU", t, checklist());
        assertTrue(p.contains("[0] (00:00) CLIENT"), p.substring(0, 300));
        assertTrue(p.contains("[61] (01:01) OPERATOR"));
        assertFalse(p.contains("2026-05-12T"));
        assertTrue(p.contains("[5] (00:05) OPERATOR: " + "x".repeat(320) + "…"));
    }

    @Test
    void scaleCriterionMayCiteClientMessage() {
        String c6client = GOOD.replace("{\"criterion_id\":\"c6\",\"answer\":\"10\",\"is_na\":false,\"reason\":\"Ясно.\",\"evidence_ids\":[3,5]",
                "{\"criterion_id\":\"c6\",\"answer\":\"10\",\"is_na\":false,\"reason\":\"Ясно.\",\"evidence_ids\":[4]");
        assertNotEquals(GOOD, c6client);
        assertTrue(new LlmResponseValidator(MAPPER, engine).validate(c6client, checklist(), chat()).ok());
    }
}
