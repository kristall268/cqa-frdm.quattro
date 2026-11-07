package com.cqafrdm.conversation;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConversationSummaryDto> list(@RequestParam(required = false) String groupId,
                                             @RequestParam(required = false) String language,
                                             @RequestParam(required = false) String channel) {
        return service.list(groupId, language, channel);
    }

    @GetMapping("/{id}")
    public ConversationDto get(@PathVariable String id) {
        return service.get(id);
    }
}