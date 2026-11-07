package com.cqafrdm.evaluation.model;

/** Одна ступень шкалы, например value=15, label="Основной вопрос решён, пропущена деталь". */
public record ScaleStep(int value, String label) {}
