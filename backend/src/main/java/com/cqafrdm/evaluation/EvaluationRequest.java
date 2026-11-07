package com.cqafrdm.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EvaluationRequest(@NotBlank String conversationId, @NotNull Long checklistVersionId) {}