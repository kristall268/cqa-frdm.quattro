package com.cqafrdm.appeal;

import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.common.ValidationException;
import com.cqafrdm.evaluation.Evaluation;
import com.cqafrdm.evaluation.EvaluationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppealService {

    private final AppealRepository appeals;
    private final EvaluationRepository evaluations;

    public AppealService(AppealRepository appeals, EvaluationRepository evaluations) {
        this.appeals = appeals;
        this.evaluations = evaluations;
    }

    @Transactional
    public AppealDto create(AppealCreateRequest req) {
        Evaluation e = evaluations.findById(req.evaluationId())
                .orElseThrow(() -> new NotFoundException("Оценка не найдена: " + req.evaluationId()));
        if (!"OK".equals(e.getStatus()) || e.getResult() == null) {
            throw new ValidationException("Нельзя оспорить неуспешную оценку");
        }
        boolean exists = e.getResult().criteria().stream()
                .anyMatch(c -> c.criterionId().equals(req.criterionId()));
        if (!exists) {
            throw new ValidationException("В оценке нет критерия " + req.criterionId());
        }
        List<Integer> evidence = req.evidenceIds() == null ? List.of() : req.evidenceIds();
        Appeal saved = appeals.save(new Appeal(e.getId(), req.criterionId(), req.comment().trim(), evidence));
        return AppealDto.from(saved, e.getConversationId());
    }

    @Transactional(readOnly = true)
    public List<AppealDto> list(String status) {
        List<Appeal> list = (status == null || status.isBlank())
                ? appeals.findAll()
                : appeals.findByStatusOrderByCreatedAtDescIdDesc(status);
        return list.stream().map(a -> AppealDto.from(a, conversationOf(a))).toList();
    }

    private String conversationOf(Appeal a) {
        return evaluations.findById(a.getEvaluationId()).map(Evaluation::getConversationId).orElse(null);
    }
}