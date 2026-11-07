package com.cqafrdm.appeal;

import jakarta.validation.constraints.NotBlank;

public record AppealDecisionRequest(@NotBlank String decision, String newAnswer, Boolean newIsNa, String reason) {}