package com.cqafrdm.dataset;

import jakarta.validation.constraints.NotBlank;

public record ImportRequest(@NotBlank String groupId) {}