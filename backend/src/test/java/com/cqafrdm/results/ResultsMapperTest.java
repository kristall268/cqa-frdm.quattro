package com.cqafrdm.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.evaluation.CheckedRange;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.EvaluationResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Формат сдачи результатов (приложение к гайду, JSON 1.0). */
class ResultsMapperTest {

    static CriterionResult r(String id, CriterionType type, int weight, String answer, boolean na, Double points,
                             List<Integer> ev, CheckedRange range) {
        return new CriterionResult(id, id, type, BigDecimal.valueOf(weight), na ? null : answer, na,
                points == null ? null : BigDecimal.valueOf(points), "обоснование " + id, ev, range);
    }

    /** Пример из гайда: contact=true, discovery=N/A, identification=N/A, consultation=15, empathy=true,
     *  dialogue=7, closure=true, critical=false -> final_score 85.38? (считаем сами по весам). */
    static List<CriterionResult> sample() {
        List<CriterionResult> l = new ArrayList<>();
        l.add(r("c1", CriterionType.BINARY, 10, "YES", false, 10.0, List.of(3), null));
        l.add(r("c2", CriterionType.BINARY, 15, null, true, null, List.of(0), null));
        l.add(r("c3", CriterionType.BINARY, 20, null, true, null, List.of(0), null));
        l.add(r("c4", CriterionType.SCALE, 20, "15", false, 15.0, List.of(4, 5), null));
        l.add(r("c5", CriterionType.BINARY, 15, "YES", false, 15.0, List.of(3, 4), null));
        l.add(r("c6", CriterionType.SCALE, 15, "7", false, 10.5, List.of(4, 5), null));
        l.add(r("c7", CriterionType.BINARY, 5, "YES", false, 5.0, List.of(7), null));
        l.add(r("c8", CriterionType.FLAG, 0, "NO", false, null, List.of(), new CheckedRange(0, 7)));
        return l;
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsCodesValuesAndOneBasedTurns() {
        Map<String, Object> e = ResultsMapper.toEntry("demo-001", new EvaluationResult(sample(), null, false));
        assertEquals("ok", e.get("status"));
        List<Map<String, Object>> c = (List<Map<String, Object>>) e.get("criteria");
        assertEquals(8, c.size());
        assertEquals(ResultsMapper.CODES, c.stream().map(x -> x.get("criterion_id")).toList());
        assertEquals(true, c.get(0).get("value"));
        assertNull(c.get(1).get("value"));                       // N/A -> null
        assertEquals(15, c.get(3).get("value"));                  // шкала — число до учёта веса
        assertEquals(7, c.get(5).get("value"));
        assertEquals(false, c.get(7).get("value"));               // critical
        assertEquals(List.of(4), c.get(0).get("evidence_turns")); // индекс 3 -> реплика 4
        assertEquals(List.of(1, 8), c.get(7).get("evidence_turns")); // нет доказательств -> границы участка
        Map<String, Object> score = (Map<String, Object>) e.get("score");
        // применимые веса 10+20+15+15+5 = 65, баллы 10+15+15+10.5+5 = 55.5 -> 85.38
        assertEquals(new BigDecimal("85.38"), score.get("final_score"));
        assertEquals(false, score.get("critical_flag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void criticalFlagDoesNotZeroScore() {
        List<CriterionResult> l = sample();
        l.set(7, r("c8", CriterionType.FLAG, 0, "YES", false, null, List.of(5), null));
        Map<String, Object> score = (Map<String, Object>) ResultsMapper.toEntry("x", new EvaluationResult(l, null, true)).get("score");
        assertEquals(true, score.get("critical_flag"));
        assertEquals(new BigDecimal("85.38"), score.get("final_score"));
    }

    @Test
    void extraCriterionIsIgnoredAndMissingOneIsError() {
        List<CriterionResult> l = sample();
        l.add(r("c9", CriterionType.BINARY, 10, "NO", false, 0.0, List.of(1), null)); // добавленный критерий
        assertEquals("ok", ResultsMapper.toEntry("x", new EvaluationResult(l, null, false)).get("status"));
        List<CriterionResult> m = sample();
        m.remove(4);
        Map<String, Object> e = ResultsMapper.toEntry("x", new EvaluationResult(m, null, false));
        assertEquals("error", e.get("status"));
        assertNull(e.get("score"));
        assertTrue(((String) e.get("error")).contains("empathy"));
    }

    @Test
    void errorEntryKeepsId() {
        Map<String, Object> e = ResultsMapper.error("demo-002", "Не удалось получить ответ модели.");
        assertEquals("demo-002", e.get("conversation_id"));
        assertEquals("error", e.get("status"));
        assertEquals(List.of(), e.get("criteria"));
        assertNull(e.get("score"));
    }
}
