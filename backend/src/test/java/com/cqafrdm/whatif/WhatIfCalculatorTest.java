package com.cqafrdm.whatif;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.checklist.ScaleStep;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.cqafrdm.evaluation.TestData.answers;
import static com.cqafrdm.evaluation.TestData.checklist;
import static org.junit.jupiter.api.Assertions.*;

class WhatIfCalculatorTest {

    private final ScoringEngine engine = new ScoringEngine();
    private final EvaluationResult base = engine.compute(checklist(), answers("NO", "YES", "YES", "10", "YES", "10", "YES", "NO")); // 80

    private static Criterion withWeight(Criterion c, int w) {
        return new Criterion(c.id(), c.title(), c.section(), c.rule(), c.type(), BigDecimal.valueOf(w), c.naAllowed(), c.naCondition(), c.scale());
    }

    @Test
    void weightChangeRecomputesWithoutModel() {
        List<Criterion> cl = new ArrayList<>(checklist());
        cl.set(0, withWeight(cl.get(0), 30));            // c1 «Нет» стал весить 30
        var i = WhatIfCalculator.compute("p1", base, cl, engine);
        assertEquals(80.0, i.before().doubleValue());
        assertEquals(66.67, i.after().doubleValue());    // 80 / 120
        assertEquals("OK", i.status());
        assertTrue(i.delta().doubleValue() < 0);
    }

    @Test
    void newCriterionAndInvalidScaleAreReportedNotGuessed() {
        List<Criterion> cl = new ArrayList<>(checklist());
        cl.add(new Criterion("c9", "Персонализация", "s", "r", CriterionType.BINARY, BigDecimal.TEN, false, null, null));
        Criterion c4 = cl.get(3);
        cl.set(3, new Criterion("c4", c4.title(), c4.section(), c4.rule(), CriterionType.SCALE, c4.weight(), true, c4.naCondition(),
                List.of(new ScaleStep(0, "0"), new ScaleStep(5, "5"), new ScaleStep(20, "20"))));   // ответа «10» больше нет
        var i = WhatIfCalculator.compute("p1", base, cl, engine);
        assertEquals("PARTIAL", i.status());
        assertTrue(i.note().contains("c9") && i.note().contains("c4"), i.note());
        assertEquals(87.5, i.after().doubleValue());     // без c4 и c9: 70 / 80
    }
}
