package com.cqafrdm.evaluation;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{id}")
    public EvaluationDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<EvaluationDto> list(@RequestParam String conversationId) {
        return service.listByConversation(conversationId);
    }
}