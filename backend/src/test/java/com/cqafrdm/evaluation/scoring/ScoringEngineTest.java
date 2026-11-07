package com.cqafrdm.evaluation.scoring;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.checklist.ScaleStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.cqafrdm.evaluation.TestData.answers;
import static com.cqafrdm.evaluation.TestData.checklist;
import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();

    @Test
    void allYesAndTopScaleGives100() {
        EvaluationResult r = engine.compute(checklist(), answers("YES", "YES", "YES", "20", "YES", "10", "YES", "NO"));
        assertEquals(100.0, r.totalScore().doubleValue());
        assertFalse(r.grossError());
    }

    @Test
    void scaleIsProportionalToWeight() {
        // c6 = 7 из 10 при весе 15 -> 10.5 балла (пример из чек-листа)
        EvaluationResult r = engine.compute(checklist(), answers("YES", "YES", "YES", "20", "YES", "7", "YES", "NO"));
        assertEquals(10.5, r.criteria().get(5).points().doubleValue());
        assertEquals(95.5, r.totalScore().doubleValue()); // 95.5 / 100 * 100
    }

    @Test
    void naIsExcludedAndWeightRedistributed() {
        // c2 (15) N/A: применимые веса = 85; баллы: 10 + 20 + 15 + 15 + 15*7/10 + 5 = 75.5 -> 100*75.5/85 = 88.82
        EvaluationResult r = engine.compute(checklist(), answers("YES", null, "YES", "15", "YES", "7", "YES", "NO"));
        assertNull(r.criteria().get(1).points());
        assertTrue(r.criteria().get(1).isNa());
        assertEquals(88.82, r.totalScore().doubleValue());
    }

    @Test
    void noGivesZero() {
        EvaluationResult r = engine.compute(checklist(), answers("NO", "YES", "YES", "20", "YES", "10", "NO", "NO"));
        assertEquals(0.0, r.criteria().get(0).points().doubleValue());
        assertEquals(85.0, r.totalScore().doubleValue());
    }

    @Test
    void grossErrorIsSeparateFlagAndDoesNotZeroScore() {
        EvaluationResult r = engine.compute(checklist(), answers("YES", "YES", "YES", "20", "YES", "10", "YES", "YES"));
        assertTrue(r.grossError());
        assertEquals(100.0, r.totalScore().doubleValue());
        assertNull(r.criteria().get(7).points());
    }

    @Test
    void noApplicableWeightedCriteriaGivesNullTotal() {
        List<Criterion> cl = List.of(
                new Criterion("a", "A", "s", "r", CriterionType.BINARY, BigDecimal.valueOf(10), true, "cond", null),
                new Criterion("f", "F", "s", "r", CriterionType.FLAG, BigDecimal.valueOf(0), false, null, null));
        var res = new ArrayList<com.cqafrdm.evaluation.CriterionResult>();
        res.add(new com.cqafrdm.evaluation.CriterionResult("a", "A", CriterionType.BINARY, BigDecimal.valueOf(10), null, true, null, "na", List.of(), null));
        res.add(new com.cqafrdm.evaluation.CriterionResult("f", "F", CriterionType.FLAG, BigDecimal.valueOf(0), "NO", false, null, "ok", List.of(), null));
        assertNull(engine.compute(cl, res).totalScore());
    }

    @Test
    void roundsToTwoDecimalsHalfUp() {
        // 2 критерия весом 3 и 6: YES + NO = 3/9 = 33.333.. -> 33.33 ; scale 2 из 3 при весе 1 -> 0.67
        List<Criterion> cl = List.of(
                new Criterion("a", "A", "s", "r", CriterionType.BINARY, BigDecimal.valueOf(3), false, null, null),
                new Criterion("b", "B", "s", "r", CriterionType.BINARY, BigDecimal.valueOf(6), false, null, null));
        var res = List.of(
                new com.cqafrdm.evaluation.CriterionResult("a", "A", CriterionType.BINARY, BigDecimal.valueOf(3), "YES", false, null, "x", List.of(0), null),
                new com.cqafrdm.evaluation.CriterionResult("b", "B", CriterionType.BINARY, BigDecimal.valueOf(6), "NO", false, null, "x", List.of(0), null));
        assertEquals(33.33, engine.compute(cl, res).totalScore().doubleValue());
        Criterion s = new Criterion("s", "S", "s", "r", CriterionType.SCALE, BigDecimal.valueOf(1), false, null,
                List.of(new ScaleStep(0, "0"), new ScaleStep(2, "2"), new ScaleStep(3, "3")));
        assertEquals(0.67, ScoringEngine.round2(engine.points(s, "2", false)));
    }

    @Test
    void naRejectedWhenNotAllowed() {
        // c1 не допускает N/A
        assertThrows(IllegalArgumentException.class,
                () -> engine.compute(checklist(), answers(null, "YES", "YES", "20", "YES", "10", "YES", "NO")));
    }

    @Test
    void invalidScaleValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.compute(checklist(), answers("YES", "YES", "YES", "12", "YES", "10", "YES", "NO")));
    }

    @Test
    void appealOverrideRecomputesAndKeepsOriginal() {
        EvaluationResult original = engine.compute(checklist(), answers("NO", "YES", "YES", "10", "YES", "10", "YES", "NO"));
        assertEquals(80.0, original.totalScore().doubleValue()); // 0 + 15 + 20 + 10 + 15 + 15 + 5
        EvaluationResult fin = engine.applyOverride(checklist(), original, "c4", "20", false, "Ответ был полным");
        assertEquals(90.0, fin.totalScore().doubleValue());
        assertEquals("10", original.criteria().get(3).answer());     // исходная оценка не изменилась
        assertEquals("20", fin.criteria().get(3).answer());
        assertTrue(fin.criteria().get(3).reason().contains("Ответ был полным"));

        EvaluationResult na = engine.applyOverride(checklist(), original, "c2", null, true, "Данных хватало");
        assertEquals(76.47, na.totalScore().doubleValue()); // 65 / 85
    }

    @Test
    void sameInputGivesSameScore() {
        var a = engine.compute(checklist(), answers("YES", null, "NO", "15", "YES", "4", null, "NO"));
        var b = engine.compute(checklist(), answers("YES", null, "NO", "15", "YES", "4", null, "NO"));
        assertEquals(a, b);
    }

    @Test
    void checklistValidation() {
        assertTrue(engine.validateChecklist(checklist()).isEmpty());
        List<Criterion> bad = List.of(
                new Criterion("x", "", "s", "r", CriterionType.SCALE, BigDecimal.valueOf(10), false, null, List.of(new ScaleStep(5, "5"))),
                new Criterion("x", "Y", "s", "r", CriterionType.FLAG, BigDecimal.valueOf(5), false, null, null));
        List<String> errors = engine.validateChecklist(bad);
        assertTrue(errors.size() >= 4, errors.toString());
    }
}
