package com.cqafrdm.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationResult(List<CriterionResult> criteria, BigDecimal totalScore, boolean grossError) {}