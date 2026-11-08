package com.cqafrdm.evaluation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Локальная модель через нативный API Ollama (POST /api/chat) — данные не покидают компьютер.
 *
 * <p>Почему не OpenAI-совместимый /v1: там нельзя передать размер контекста, а окно Ollama по умолчанию
 * (2–4 тыс. токенов, зависит от версии) меньше нашего промпта (инструкции + чек-лист + транскрипт).
 * Ollama молча обрезает начало — то есть инструкции. Здесь {@code options.num_ctx} передаётся в каждом
 * запросе и подбирается под размер промпта; переполнение определяется по {@code prompt_eval_count}
 * и даёт явную ошибку (оценка FAILED), а не тихо испорченный ответ. Настраивать сервер Ollama
 * (OLLAMA_CONTEXT_LENGTH, Modelfile) не нужно.
 *
 * <p>Детерминизм: temperature=0, фиксированный seed. Ответ — JSON-режим ({@code format:"json"}).
 * Блок &lt;think&gt; рассуждающих моделей вырезается до разбора.
 *
 * <p>.env: OLLAMA_NUM_CTX (минимум окна, 8192), OLLAMA_NUM_CTX_MAX (32768), OLLAMA_NUM_PREDICT (4096),
 * OLLAMA_TIMEOUT_SECONDS (600), OLLAMA_KEEP_ALIVE (30m), OLLAMA_SEED (42), OLLAMA_THINK (пусто | true | false).
 */
public class OllamaLlmClient implements LlmClient {

    static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String root;
    private final List<String> models;
    final int numCtxMin;
    final int numCtxMax;
    final int numPredict;
    private final Duration timeout;
    private final String keepAlive;
    private final int seed;
    private final Boolean think;

    public OllamaLlmClient(ObjectMapper mapper, String baseUrl, String model, Function<String, String> env) {
        this.mapper = mapper;
        this.root = rootUrl(baseUrl);
        this.models = Arrays.stream(model.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
        if (models.isEmpty()) throw new IllegalArgumentException("Не задана модель Ollama (LLM_MODEL)");
        this.numCtxMin = intEnv(env, "OLLAMA_NUM_CTX", 8192);
        this.numCtxMax = Math.max(numCtxMin, intEnv(env, "OLLAMA_NUM_CTX_MAX", 32768));
        this.numPredict = intEnv(env, "OLLAMA_NUM_PREDICT", 4096);
        this.timeout = Duration.ofSeconds(Math.max(10, intEnv(env, "OLLAMA_TIMEOUT_SECONDS", 600)));
        String ka = env.apply("OLLAMA_KEEP_ALIVE");
        this.keepAlive = ka == null || ka.isBlank() ? "30m" : ka.trim();
        this.seed = intEnv(env, "OLLAMA_SEED", 42);
        String th = env.apply("OLLAMA_THINK");
        this.think = th == null || th.isBlank() ? null : Boolean.parseBoolean(th.trim());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** http://localhost:11434, …/v1, …/api, …/api/chat -> http://localhost:11434 */
    static String rootUrl(String baseUrl) {
        String u = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        for (String suffix : List.of("/api/chat", "/v1/chat/completions", "/api", "/v1")) {
            if (u.endsWith(suffix)) { u = u.substring(0, u.length() - suffix.length()); break; }
        }
        return u;
    }

    /** Похоже на Ollama: порт 11434 или хост/сервис с именем ollama. */
    public static boolean looksLikeOllama(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase();
        return u.contains(":11434") || u.contains("://ollama");
    }

    @Override
    public String modelName() { return models.get(0); }

    public String baseUrl() { return root; }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        List<String> errors = new ArrayList<>();
        for (String model : models) {
            try {
                return completeWith(model, request);
            } catch (ModelMissing e) {
                errors.add(e.getMessage());           // пробуем следующую модель из списка
            }
        }
        throw new LlmException(String.join(" | ", errors));
    }

    private static final class ModelMissing extends Exception {
        ModelMissing(String m) { super(m); }
    }

    private LlmResponse completeWith(String model, LlmRequest request) throws LlmException, ModelMissing {
        long approx = promptChars(request) / 4;                   // мягкая оценка: заведомо не влезет даже в максимум
        if (approx + numPredict > numCtxMax) {
            throw new LlmException("Промпт ≈" + approx + " токенов не помещается в контекст " + numCtxMax
                    + " — увеличьте OLLAMA_NUM_CTX_MAX (если модель поддерживает такое окно)");
        }
        int ctx = contextFor(request);
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(root + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(request, model, ctx)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Запрос к Ollama прерван", e);
        } catch (HttpTimeoutException e) {
            throw new LlmException("Ollama не ответила за " + timeout.toSeconds() + " с (модель " + model
                    + "): возьмите модель меньше или увеличьте OLLAMA_TIMEOUT_SECONDS", e);
        } catch (ConnectException e) {
            throw new LlmException("Ollama недоступна по адресу " + root
                    + ": запустите её (ollama serve) или проверьте LLM_BASE_URL", e);
        } catch (Exception e) {
            throw new LlmException("Ollama: ошибка соединения: " + e.getMessage(), e);
        }
        int code = resp.statusCode();
        String b = resp.body() == null ? "" : resp.body();
        String low = b.toLowerCase();
        if (code == 404 || (code >= 400 && low.contains("model") && low.contains("not found"))) {
            throw new ModelMissing("Модель «" + model + "» не скачана в Ollama: выполните ollama pull " + model);
        }
        if (code != 200) throw new LlmException("Ollama HTTP " + code + ": " + shorten(errorText(b)));
        return parse(b, model, ctx);
    }

    static long promptChars(LlmRequest request) {
        long chars = 0;
        for (LlmMessage m : request.messages()) chars += m.content() == null ? 0 : m.content().length();
        return chars;
    }

    /** Окно: промпт (≈3 символа на токен — с запасом для кириллицы) + ответ + 512, кратно 2048, в пределах [min, max]. */
    int contextFor(LlmRequest request) {
        long need = promptChars(request) / 3 + numPredict + 512;
        long ctx = Math.max(numCtxMin, ((need + 2047) / 2048) * 2048);
        return (int) Math.min(numCtxMax, ctx);
    }

    String body(LlmRequest request, String model, int ctx) {
        ObjectNode r = mapper.createObjectNode();
        r.put("model", model);
        r.put("stream", false);
        r.put("format", "json");
        r.put("keep_alive", keepAlive);
        if (think != null) r.put("think", think);
        ObjectNode o = r.putObject("options");
        o.put("temperature", 0);
        o.put("seed", seed);
        o.put("num_ctx", ctx);
        o.put("num_predict", numPredict);
        ArrayNode msgs = r.putArray("messages");
        for (LlmMessage m : request.messages()) msgs.addObject().put("role", m.role()).put("content", m.content());
        return r.toString();
    }

    LlmResponse parse(String body, String model, int ctx) throws LlmException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new LlmException("Не удалось разобрать ответ Ollama: " + e.getMessage(), e);
        }
        if (root.hasNonNull("error")) throw new LlmException("Ollama: " + shorten(root.path("error").asText()));
        int in = root.path("prompt_eval_count").asInt(0), out = root.path("eval_count").asInt(0);
        // Ollama не сообщает об обрезке промпта: если промпт занял всё окно — начало (инструкции) потеряно
        if (in > 0 && in >= ctx - 32) {
            throw new LlmException("Промпт (" + in + " токенов) занял всё окно " + ctx
                    + ": Ollama обрезала начало. Увеличьте OLLAMA_NUM_CTX_MAX");
        }
        if ("length".equals(root.path("done_reason").asText())) {
            throw new LlmException("Ответ модели обрезан на " + out + " токенах: увеличьте OLLAMA_NUM_PREDICT");
        }
        String content = THINK.matcher(root.path("message").path("content").asText("")).replaceAll("").trim();
        if (content.isEmpty()) throw new LlmException("Пустой ответ Ollama (модель " + model + ")");
        return new LlmResponse(content, in, out, root.path("model").asText(model));
    }

    private String errorText(String b) {
        try { return mapper.readTree(b).path("error").asText(b); } catch (Exception e) { return b; }
    }

    private static int intEnv(Function<String, String> env, String key, int def) {
        String v = env.apply(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
