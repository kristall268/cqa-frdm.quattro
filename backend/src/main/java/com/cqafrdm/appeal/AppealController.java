package com.cqafrdm.appeal;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService service;

    public AppealController(AppealService service) {
        this.service = service;
    }

    @PostMapping
    public AppealDto create(@Valid @RequestBody AppealCreateRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<AppealDto> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }
}