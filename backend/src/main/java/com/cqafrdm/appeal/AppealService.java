package com.cqafrdm.appeal;

import com.cqafrdm.checklist.ChecklistService;
import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.common.ValidationException;
import com.cqafrdm.evaluation.CriterionResult;
import com.cqafrdm.evaluation.Evaluation;
import com.cqafrdm.evaluation.EvaluationRepository;
import com.cqafrdm.evaluation.EvaluationResult;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppealService {

    private final AppealRepository appeals;
    private final EvaluationRepository evaluations;
    private final ChecklistService checklists;
    private final ScoringEngine scoringEngine;

    public AppealService(AppealRepository appeals, EvaluationRepository evaluations,
                         ChecklistService checklists, ScoringEngine scoringEngine) {
        this.appeals = appeals;
        this.evaluations = evaluations;
        this.checklists = checklists;
        this.scoringEngine = scoringEngine;
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

    /** Пересчёт идёт поверх последнего final по этой оценке. evaluation.result не меняется. */
    @Transactional
    public AppealDto decide(Long id, AppealDecisionRequest req) {
        Appeal a = appeals.findById(id)
                .orElseThrow(() -> new NotFoundException("Апелляция не найдена: " + id));
        if ("DECIDED".equals(a.getStatus())) {
            throw new ValidationException("Апелляция уже рассмотрена");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new ValidationException("Укажите причину решения");
        }
        boolean changed = "CHANGED".equals(req.decision());
        if (!changed && !"CONFIRMED".equals(req.decision())) {
            throw new ValidationException("decision: CONFIRMED | CHANGED");
        }

        Evaluation e = evaluations.findById(a.getEvaluationId())
                .orElseThrow(() -> new NotFoundException("Оценка не найдена: " + a.getEvaluationId()));
        if (e.getResult() == null) {
            throw new ValidationException("У оценки нет результата");
        }
        List<Criterion> criteria = checklists.getEntity(e.getChecklistVersionId()).getCriteria();

        EvaluationResult base = appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId()).stream()
                .filter(x -> "DECIDED".equals(x.getStatus()) && x.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());

        CriterionResult current = base.criteria().stream()
                .filter(c -> c.criterionId().equals(a.getCriterionId())).findFirst()
                .orElseThrow(() -> new ValidationException("В оценке нет критерия " + a.getCriterionId()));

        String answer = changed ? req.newAnswer() : current.answer();
        boolean isNa = changed ? Boolean.TRUE.equals(req.newIsNa()) : current.isNa();

        EvaluationResult fin;
        try {
            fin = scoringEngine.applyOverride(criteria, base, a.getCriterionId(), answer, isNa, req.reason().trim());
        } catch (IllegalArgumentException ex) { // недопустимый ответ или N/A запрещён
            throw new ValidationException(ex.getMessage());
        }
        a.decide(req.decision(), changed ? answer : null, changed ? isNa : null, req.reason().trim(), fin);
        return AppealDto.from(a, e.getConversationId());
    }

    private String conversationOf(Appeal a) {
        return evaluations.findById(a.getEvaluationId()).map(Evaluation::getConversationId).orElse(null);
    }
}