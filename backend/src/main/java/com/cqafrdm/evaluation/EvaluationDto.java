package com.cqafrdm.evaluation;

import com.cqafrdm.appeal.AppealDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EvaluationDto(Long id, String conversationId, Long checklistVersionId,
                            String status, String error,
                            EvaluationResult original, List<AppealDto> appeals,
                            @JsonProperty("final") EvaluationResult finalResult,
                            String model, BigDecimal costUsd, OffsetDateTime createdAt) {}