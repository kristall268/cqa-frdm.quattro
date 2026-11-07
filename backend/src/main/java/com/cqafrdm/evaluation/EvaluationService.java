package com.cqafrdm.evaluation;

import com.cqafrdm.appeal.Appeal;
import com.cqafrdm.appeal.AppealDto;
import com.cqafrdm.appeal.AppealRepository;
import com.cqafrdm.common.NotFoundException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    private final EvaluationRepository evaluations;
    private final AppealRepository appeals;

    public EvaluationService(EvaluationRepository evaluations, AppealRepository appeals) {
        this.evaluations = evaluations;
        this.appeals = appeals;
    }

    @Transactional(readOnly = true)
    public EvaluationDto get(Long id) {
        Evaluation e = evaluations.findById(id)
                .orElseThrow(() -> new NotFoundException("Оценка не найдена: " + id));
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDto> listByConversation(String conversationId) {
        return evaluations.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId)
                .stream().map(this::toDto).toList();
    }

    private EvaluationDto toDto(Evaluation e) {
        List<Appeal> list = appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId());
        EvaluationResult finalResult = list.stream()
                .filter(a -> "DECIDED".equals(a.getStatus()) && a.getFinalResult() != null)
                .max(Comparator.comparing(Appeal::getDecidedAt))
                .map(Appeal::getFinalResult)
                .orElse(e.getResult());
        List<AppealDto> appealDtos = list.stream()
                .map(a -> AppealDto.from(a, e.getConversationId())).toList();
        return new EvaluationDto(e.getId(), e.getConversationId(), e.getChecklistVersionId(),
                e.getStatus(), e.getError(), e.getResult(), appealDtos, finalResult,
                e.getModel(), e.getCostUsd(), e.getCreatedAt());
    }
}