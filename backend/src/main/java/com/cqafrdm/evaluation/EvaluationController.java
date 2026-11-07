package com.cqafrdm.evaluation;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public EvaluationDto evaluate(@Valid @RequestBody EvaluationRequest request) {
        return service.evaluate(request.conversationId(), request.checklistVersionId());
    }

    @PostMapping("/batch")
    public Map<String, Integer> batch(@Valid @RequestBody BatchRequest request) {
        return Map.of("started", service.evaluateGroup(request.groupId(), request.checklistVersionId()));
    }

    @GetMapping("/{id}")
    public EvaluationDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<EvaluationDto> list(@RequestParam String conversationId) {
        return service.listByConversation(conversationId);
    }
}