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
import java.util.List;

/**
 * Клиент для любого OpenAI-совместимого Chat Completions API:
 * Gemini (https://generativelanguage.googleapis.com/v1beta/openai), OpenAI, Groq, OpenRouter, DeepSeek...
 * Ключ берётся только из переменной окружения / .env на сервере.
 * 429 и 5xx — повтор с паузой (бесплатный тариф Gemini имеет лимит запросов в минуту).
 * LLM_MODEL может быть списком через запятую: "gemini-3.8-flash,gemini-3.8-flash-lite" —
 * если модель перегружена (503/429) или недоступна (404), берётся следующая.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final int MAX_HTTP_ATTEMPTS = 4;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;
    private final java.util.List<String> models;
    private volatile boolean jsonModeSupported = true;
    /** Модели, ответившие 404 (нет такой) — больше не пробуем. */
    private final java.util.Set<String> deadModels = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Есть резервный провайдер — не ждём перегруженный сервис, сразу отдаём ошибку. */
    private volatile boolean fastFail = false;

    /** Таймаут одного HTTP-запроса (LLM_TIMEOUT_SECONDS), по умолчанию 120 с. */
    private volatile int timeoutSeconds = 120;

    public OpenAiCompatibleLlmClient timeoutSeconds(int seconds) {
        this.timeoutSeconds = Math.max(10, seconds);
        return this;
    }

    public OpenAiCompatibleLlmClient fastFail() {
        this.fastFail = true;
        return this;
    }

    public OpenAiCompatibleLlmClient(ObjectMapper mapper, String baseUrl, String apiKey, String model) {
        this.mapper = mapper;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        if (apiKey == null || apiKey.isBlank() || !apiKey.matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException("API-ключ для " + base + " выглядит неверно (пустой, с пробелами "
                    + "или не латиницей — возможно, в .env остался шаблон вместо настоящего ключа)");
        this.apiKey = apiKey;
        this.models = java.util.Arrays.stream(model.split(","))
                .map(String::trim).filter(m -> !m.isEmpty()).toList();
        if (this.models.isEmpty()) throw new IllegalArgumentException("LLM_MODEL пуст");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public String modelName() { return models.get(0); }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<String> errors = new java.util.ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            if (deadModels.contains(models.get(i))) { errors.add(models.get(i) + ": недоступна (404)"); continue; }
            boolean hasFallback = i < models.size() - 1;
            try {
                return completeWith(models.get(i), request, fastFail ? 1 : hasFallback ? 2 : MAX_HTTP_ATTEMPTS);
            } catch (FallbackException e) {
                errors.add(models.get(i) + ": " + shortError(e.getMessage()));
            }
        }
        throw new LlmException(String.join(" | ", errors));
    }

    /** Ошибки, при которых имеет смысл попробовать следующую модель. */
    private static final class FallbackException extends Exception {
        FallbackException(String m) { super(m); }
    }

    private LlmResponse completeWith(String model, LlmRequest request, int maxAttempts)
            throws LlmException, FallbackException {
        String lastError = "нет ответа";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = http.send(HttpRequest.newBuilder(URI.create(endpoint))
                                .timeout(Duration.ofSeconds(timeoutSeconds))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKey)
                                .POST(HttpRequest.BodyPublishers.ofString(body(request, model)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Запрос к модели прерван", e);
            } catch (Exception e) {
                lastError = "Сеть: " + e.getMessage();
                if (attempt < maxAttempts) sleep(backoffMs(attempt, null));
                continue;
            }
            int code = resp.statusCode();
            if (code == 200) return parse(resp.body(), model);
            if (code == 400 && jsonModeSupported && resp.body().contains("response_format")) {
                jsonModeSupported = false;            // провайдер не знает json-режим — шлём без него
                attempt--;
                continue;
            }
            lastError = "HTTP " + code + ": " + shorten(resp.body());
            if (code == 413) throw new FallbackException(lastError);          // запрос велик для этой модели — следующая
            if (code == 404) {                                               // модель недоступна
                deadModels.add(model);
                throw new FallbackException(lastError);
            }
            if (code == 429 || code >= 500) {
                if (attempt < maxAttempts) sleep(backoffMs(attempt, resp.headers().firstValue("Retry-After").orElse(null)));
                continue;
            }
            throw new LlmException(lastError);       // 400/401/403 — повтор не поможет
        }
        throw new FallbackException(lastError);
    }

    private String body(LlmRequest request, String model) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        if (jsonModeSupported) root.putObject("response_format").put("type", "json_object");
        ArrayNode msgs = root.putArray("messages");
        for (LlmMessage m : request.messages()) msgs.addObject().put("role", m.role()).put("content", m.content());
        return root.toString();
    }

    private LlmResponse parse(String body, String model) throws LlmException {
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

    /** Из JSON-ошибки провайдера берём только "HTTP 503: <message>". */
    private static String shortError(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{0,200})").matcher(s);
        String code = s.startsWith("HTTP ") ? s.substring(0, Math.min(s.length(), 8)) : "";
        return m.find() ? (code + " " + m.group(1)).trim() : shorten(s);
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
