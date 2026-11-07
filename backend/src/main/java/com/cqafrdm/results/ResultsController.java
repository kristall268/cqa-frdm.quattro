package com.cqafrdm.results;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Выгрузка результатов в формате сдачи (results/dev/results.json, results/prod/results.json). */
@RestController
@RequestMapping("/api/results")
public class ResultsController {

    private final ResultsExportService service;

    public ResultsController(ResultsExportService service) {
        this.service = service;
    }

    /** GET /api/results/export?groupId=DEV[&checklistVersionId=1][&source=final|original] */
    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam String groupId,
                                      @RequestParam(required = false) Long checklistVersionId,
                                      @RequestParam(required = false, defaultValue = "final") String source) {
        return service.export(groupId, checklistVersionId, !"original".equalsIgnoreCase(source));
    }
}
