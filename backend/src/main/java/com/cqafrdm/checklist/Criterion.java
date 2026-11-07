package com.cqafrdm.checklist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Criterion(String id,
                        String title,
                        String section,
                        String rule,
                        CriterionType type,
                        BigDecimal weight,
                        boolean naAllowed,
                        String naCondition,
                        List<ScaleStep> scale) {}