package com.cqafrdm.evaluation;

import com.cqafrdm.checklist.CriterionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CriterionResult(String criterionId,
                              String title,
                              CriterionType type,
                              BigDecimal weight,
                              String answer,
                              @JsonProperty("isNa") boolean isNa,
                              BigDecimal points,
                              String reason,
                              List<Integer> evidenceIds,
                              CheckedRange checkedRange) {}