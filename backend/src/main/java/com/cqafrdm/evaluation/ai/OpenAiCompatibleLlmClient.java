package com.cqafrdm.evaluation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Клиент для любого OpenAI-совместимого Chat Completions API:
 * Gemini (https://generativelanguage.googleapis.com/v1beta/openai), OpenAI, Groq, OpenRouter, DeepSeek...
 * Ключ берётся только из переменной окружения / .env на сервере.
 * 429 и 5xx — повтор с паузой (бесплатный тариф Gemini имеет лимит запросов в минуту).
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final int MAX_HTTP_ATTEMPTS = 4;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private volatile boolean jsonModeSupported = true;

    public OpenAiCompatibleLlmClient(ObjectMapper mapper, String baseUrl, String apiKey, String model) {
        this.mapper = mapper;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public String modelName() { return model; }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(HttpRequest.newBuilder(URI.create(endpoint))
                                .timeout(Duration.ofSeconds(120))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKey)
                                .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Запрос к модели прерван", e);
            } catch (Exception e) {
                last = new LlmException("Сеть: " + e.getMessage(), e);
                sleep(backoffMs(attempt, null));
                continue;
            }
            int code = resp.statusCode();
            if (code == 200) return parse(resp.body());
            if (code == 400 && jsonModeSupported && resp.body().contains("response_format")) {
                jsonModeSupported = false;            // провайдер не знает json-режим — шлём без него
                continue;
            }
            last = new LlmException("HTTP " + code + ": " + shorten(resp.body()));
            if (code == 429 || code >= 500) {
                sleep(backoffMs(attempt, resp.headers().firstValue("Retry-After").orElse(null)));
                continue;
            }
            throw last;                              // 400/401/403/404 — повтор не поможет
        }
        throw last;
    }

    private String body(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        if (jsonModeSupported) root.putObject("response_format").put("type", "json_object");
        ArrayNode msgs = root.putArray("messages");
        for (LlmMessage m : request.messages()) msgs.addObject().put("role", m.role()).put("content", m.content());
        return root.toString();
    }

    private LlmResponse parse(String body) throws LlmException {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull())
                throw new LlmException("Пустой ответ модели: " + shorten(body));
            JsonNode usage = root.path("usage");
            return new LlmResponse(content.asText(),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    root.path("model").asText(model));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Не удалось разобрать ответ модели: " + e.getMessage(), e);
        }
    }

    private static long backoffMs(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try { return Math.min(60_000, Long.parseLong(retryAfter.trim()) * 1000); } catch (NumberFormatException ignored) { }
        }
        return switch (attempt) { case 1 -> 5_000; case 2 -> 15_000; default -> 30_000; };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
