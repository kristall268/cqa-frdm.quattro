package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.conversation.TranscriptMessage;

import java.util.List;

/**
 * Запрос к модели. messages — то, что уходит провайдеру.
 * criteria/transcript — структурированный контекст (нужен MockLlmClient, провайдеру не отправляется отдельно).
 */
public record LlmRequest(List<LlmMessage> messages, List<Criterion> criteria, List<TranscriptMessage> transcript) {}
