package com.cqafrdm.appeal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AppealCreateRequest(@NotNull Long evaluationId,
                                  @NotBlank String criterionId,
                                  @NotBlank String comment,
                                  List<Integer> evidenceIds) {}