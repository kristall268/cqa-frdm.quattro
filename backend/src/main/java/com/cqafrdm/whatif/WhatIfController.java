package com.cqafrdm.whatif;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/what-if")
public class WhatIfController {

    private final WhatIfService service;

    public WhatIfController(WhatIfService service) {
        this.service = service;
    }

    /** POST /api/what-if  {"groupId":"DEV","criteria":[...]}  -> before/after по каждому оценённому диалогу */
    @PostMapping
    public Map<String, Object> simulate(@RequestBody WhatIfRequest request) {
        return service.simulate(request);
    }
}
