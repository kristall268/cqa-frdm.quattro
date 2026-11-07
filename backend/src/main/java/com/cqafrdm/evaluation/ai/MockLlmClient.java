package com.cqafrdm.evaluation.ai;

import com.cqafrdm.evaluation.model.Criterion;
import com.cqafrdm.evaluation.model.CriterionType;
import com.cqafrdm.evaluation.model.ScaleStep;
import com.cqafrdm.conversation.TranscriptMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Детерминированная заглушка, когда LLM_API_KEY пуст: позволяет фронтенду и бэкенду работать без модели.
 * Это НЕ настоящая оценка — в reason всегда пометка «MOCK».
 */
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper;

    public MockLlmClient(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public String modelName() { return "mock"; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        List<TranscriptMessage> t = request.transcript();
        int firstOp = -1, lastOp = -1;
        for (int i = 0; i < t.size(); i++) {
            if ("operator".equalsIgnoreCase(t.get(i).speaker())) {
                if (firstOp < 0) firstOp = i;
                lastOp = i;
            }
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = root.putArray("criteria");
        for (Criterion c : request.criteria()) {
            ObjectNode o = arr.addObject().put("criterion_id", c.id());
            if (c.type() == CriterionType.FLAG) {
                o.put("answer", "NO").put("is_na", false).put("reason", "MOCK: грубых ошибок не найдено.");
                o.putArray("evidence_ids");
                o.putNull("checked_range");
            } else if (firstOp < 0) {
                if (c.naAllowed()) {
                    o.putNull("answer").put("is_na", true).put("reason", "MOCK: в диалоге нет реплик оператора.");
                    o.putArray("evidence_ids");
                    o.putNull("checked_range");
                } else {
                    o.put("answer", c.type() == CriterionType.SCALE ? "0" : "NO").put("is_na", false)
                     .put("reason", "MOCK: в диалоге нет реплик оператора.");
                    o.putArray("evidence_ids");
                    o.putObject("checked_range").put("from", 0).put("to", Math.max(0, t.size() - 1));
                }
            } else {
                String answer = c.type() == CriterionType.SCALE
                        ? String.valueOf(c.scale().stream().mapToInt(ScaleStep::value).max().orElse(0))
                        : "YES";
                int ev = "c7".equals(c.id()) ? lastOp : firstOp;
                o.put("answer", answer).put("is_na", false)
                 .put("reason", "MOCK: тестовая оценка без модели, см. реплику [" + ev + "].");
                o.putArray("evidence_ids").add(ev);
                o.putNull("checked_range");
            }
        }
        String content = root.toString();
        return new LlmResponse(content, 0, 0, "mock");
    }
}
