package com.cqafrdm.checklist;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checklists")
public class ChecklistController {

    private final ChecklistService service;

    public ChecklistController(ChecklistService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChecklistVersionDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ChecklistVersionDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ChecklistVersionDto create(@RequestBody ChecklistRequest request) {
        return service.create(request);
    }
}