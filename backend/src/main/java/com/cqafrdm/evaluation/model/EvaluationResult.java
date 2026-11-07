package com.cqafrdm.evaluation.model;

import java.util.List;

/** Итог оценки. totalScore == null, если нет применимых критериев с весом. */
public record EvaluationResult(List<CriterionResult> criteria, Double totalScore, boolean grossError) {}
