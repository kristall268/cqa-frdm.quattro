package com.cqafrdm.checklist;

import java.util.List;

public record ChecklistRequest(String name, List<Criterion> criteria) {}