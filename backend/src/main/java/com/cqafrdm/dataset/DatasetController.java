package com.cqafrdm.dataset;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetImportService service;

    public DatasetController(DatasetImportService service) {
        this.service = service;
    }

    @PostMapping("/import")
    public ImportResult importDataset(@Valid @RequestBody ImportRequest request) {
        return service.importGroup(request.groupId());
    }
}