package com.cqafrdm.conversation;

import com.cqafrdm.common.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository repository;

    public ConversationController(ConversationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public ConversationDto get(@PathVariable String id) {
        return repository.findById(id)
                .map(ConversationDto::from)
                .orElseThrow(() -> new NotFoundException("Диалог не найден: " + id));
    }
}