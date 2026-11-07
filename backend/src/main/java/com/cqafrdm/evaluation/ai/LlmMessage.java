package com.cqafrdm.evaluation.ai;

/** role: system | user | assistant */
public record LlmMessage(String role, String content) {}
