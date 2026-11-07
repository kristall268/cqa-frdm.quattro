package com.cqafrdm.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BatchRequest(@NotBlank String groupId, @NotNull Long checklistVersionId) {}