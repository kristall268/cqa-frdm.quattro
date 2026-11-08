package com.cqafrdm.evaluation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OllamaLlmClient против фейкового сервера Ollama (JDK HttpServer): тело запроса, num_ctx, ошибки. */
class OllamaLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Фейковая Ollama: на /api/chat отвечает по очереди заданными (код, тело), запоминает тела запросов. */
    private static final class FakeOllama implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new ArrayList<>();
        final List<Object[]> replies = new ArrayList<>();

        FakeOllama() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", ex -> {
                requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Object[] r = replies.isEmpty() ? new Object[]{500, "{\"error\":\"no reply\"}"} : replies.remove(0);
                byte[] b = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders((Integer) r[0], b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.start();
        }

        FakeOllama reply(int code, String body) { replies.add(new Object[]{code, body}); return this; }

        String url(String suffix) { return "http://127.0.0.1:" + server.getAddress().getPort() + suffix; }

        @Override public void close() { server.stop(0); }
    }

    private static String ok(String content, int in, int out) {
        return MAPPER.createObjectNode().put("model", "qwen2.5:7b").put("done", true).put("done_reason", "stop")
                .put("prompt_eval_count", in).put("eval_count", out)
                .set("message", MAPPER.createObjectNode().put("role", "assistant").put("content", content)).toString();
    }

    private static LlmRequest request(int chars) {
        return new LlmRequest(List.of(new LlmMessage("system", "Ты контролёр качества."),
                new LlmMessage("user", "x".repeat(chars))), List.of(), List.of());
    }

    private static Function<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m::get;
    }

    @Test
    void sendsNativeRequestWithContextWindowJsonModeAndSeed() throws Exception {
        try (FakeOllama f = new FakeOllama().reply(200, ok("{\"criteria\":[]}", 900, 40))) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url("/v1"), "qwen2.5:7b", env());
            LlmResponse r = c.complete(request(30_000));
            assertEquals("{\"criteria\":[]}", r.content());
            assertEquals(900, r.inputTokens());
            assertEquals(40, r.outputTokens());

            JsonNode body = MAPPER.readTree(f.requests.get(0));
            assertEquals("qwen2.5:7b", body.path("model").asText());
            assertFalse(body.path("stream").asBoolean(true));
            assertEquals("json", body.path("format").asText());
            assertEquals(0, body.path("options").path("temperature").asInt(-1));
            assertEquals(42, body.path("options").path("seed").asInt());
            // 30 022 символа / 3 + 4096 + 512 = 14 615 -> кратно 2048 -> 16 384
            assertEquals(16384, body.path("options").path("num_ctx").asInt());
            assertFalse(body.has("think"), "think не передаётся, пока не задан OLLAMA_THINK");
            assertEquals(2, body.path("messages").size());
        }
    }

    @Test
    void smallPromptUsesMinimumWindowAndRespectsEnv() throws Exception {
        OllamaLlmClient c = new OllamaLlmClient(MAPPER, "http://localhost:11434", "m", env());
        assertEquals(8192, c.contextFor(request(100)));
        OllamaLlmClient big = new OllamaLlmClient(MAPPER, "http://localhost:11434", "m",
                env("OLLAMA_NUM_CTX", "4096", "OLLAMA_NUM_CTX_MAX", "12288", "OLLAMA_NUM_PREDICT", "1024"));
        assertEquals(4096, big.contextFor(request(100)));
        assertEquals(12288, big.contextFor(request(60_000)), "не больше OLLAMA_NUM_CTX_MAX");
    }

    @Test
    void promptThatFillsWindowIsExplicitErrorNotSilentTruncation() throws Exception {
        try (FakeOllama f = new FakeOllama().reply(200, ok("{}", 8190, 10))) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "m", env());
            LlmException e = assertThrows(LlmException.class, () -> c.complete(request(100)));
            assertTrue(e.getMessage().contains("обрезала"), e.getMessage());
        }
    }

    @Test
    void hugePromptIsRejectedBeforeSending() throws Exception {
        try (FakeOllama f = new FakeOllama()) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "m", env("OLLAMA_NUM_CTX_MAX", "8192"));
            LlmException e = assertThrows(LlmException.class, () -> c.complete(request(40_000)));
            assertTrue(e.getMessage().contains("OLLAMA_NUM_CTX_MAX"), e.getMessage());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test
    void truncatedAnswerIsError() throws Exception {
        String body = MAPPER.readTree(ok("{\"criteria\":[", 500, 4096)).deepCopy().toString()
                .replace("\"done_reason\":\"stop\"", "\"done_reason\":\"length\"");
        try (FakeOllama f = new FakeOllama().reply(200, body)) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "m", env());
            LlmException e = assertThrows(LlmException.class, () -> c.complete(request(100)));
            assertTrue(e.getMessage().contains("OLLAMA_NUM_PREDICT"), e.getMessage());
        }
    }

    @Test
    void stripsThinkBlock() throws Exception {
        try (FakeOllama f = new FakeOllama().reply(200, ok("<think>сначала {проверю} c1</think>\n{\"criteria\":[]}", 300, 50))) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "qwen3:8b", env("OLLAMA_THINK", "false"));
            assertEquals("{\"criteria\":[]}", c.complete(request(100)).content());
            assertFalse(MAPPER.readTree(f.requests.get(0)).path("think").asBoolean(true));
        }
    }

    @Test
    void missingModelSuggestsPullAndFallsBackToNextModel() throws Exception {
        try (FakeOllama f = new FakeOllama()
                .reply(404, "{\"error\":\"model \\\"qwen2.5:14b\\\" not found, try pulling it first\"}")
                .reply(200, ok("{}", 100, 5))) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "qwen2.5:14b, qwen2.5:7b", env());
            assertEquals("{}", c.complete(request(10)).content());
            assertEquals("qwen2.5:7b", MAPPER.readTree(f.requests.get(1)).path("model").asText());
        }
        try (FakeOllama f = new FakeOllama().reply(404, "{\"error\":\"model not found\"}")) {
            OllamaLlmClient c = new OllamaLlmClient(MAPPER, f.url(""), "llama3.1", env());
            LlmException e = assertThrows(LlmException.class, () -> c.complete(request(10)));
            assertTrue(e.getMessage().contains("ollama pull llama3.1"), e.getMessage());
        }
    }

    @Test
    void unreachableServerSaysHowToStartIt() throws Exception {
        String url;
        try (FakeOllama f = new FakeOllama()) { url = f.url(""); }   // порт освобождён — соединение отклонят
        OllamaLlmClient c = new OllamaLlmClient(MAPPER, url, "m", env());
        LlmException e = assertThrows(LlmException.class, () -> c.complete(request(10)));
        assertTrue(e.getMessage().contains("ollama serve"), e.getMessage());
    }

    @Test
    void urlNormalisationAndDetection() {
        assertEquals("http://localhost:11434", OllamaLlmClient.rootUrl("http://localhost:11434/v1/"));
        assertEquals("http://localhost:11434", OllamaLlmClient.rootUrl("http://localhost:11434/api/chat"));
        assertEquals("http://localhost:11434", OllamaLlmClient.rootUrl(""));
        assertTrue(OllamaLlmClient.looksLikeOllama("http://host.docker.internal:11434"));
        assertTrue(OllamaLlmClient.looksLikeOllama("http://ollama:8080"));
        assertFalse(OllamaLlmClient.looksLikeOllama("https://generativelanguage.googleapis.com/v1beta/openai"));
        assertFalse(OllamaLlmClient.looksLikeOllama("http://localhost:1234/v1"));   // LM Studio — OpenAI-совместимый
    }

    @Test
    void aiConfigBuildsOllamaClientWithoutKey() {
        LlmClient c = AiConfig.chain(env("LLM_BASE_URL", "http://localhost:11434/v1", "LLM_MODEL", "qwen2.5:7b"), null, null, null);
        assertTrue(c instanceof OllamaLlmClient, c.getClass().getName());
        LlmClient forced = AiConfig.chain(env("LLM_PROVIDER", "openai", "LLM_BASE_URL", "http://localhost:11434/v1",
                "LLM_MODEL", "qwen2.5:7b"), null, null, null);
        assertTrue(forced instanceof OpenAiCompatibleLlmClient, "LLM_PROVIDER=openai оставляет /v1");
        LlmClient explicit = AiConfig.chain(env("LLM_PROVIDER", "ollama", "LLM_MODEL", "qwen2.5:7b"), null, null, null);
        assertTrue(explicit instanceof OllamaLlmClient, "адрес по умолчанию localhost:11434");
        assertTrue(AiConfig.describe(env("LLM_BASE_URL", "http://localhost:11434", "LLM_MODEL", "qwen2.5:7b"))
                .contains("(Ollama)"));
        assertTrue(AiConfig.isOllama(env("LLM_BASE_URL", "http://localhost:11434", "LLM_MODEL", "m"), "LLM_"));
        assertFalse(AiConfig.isOllama(env("LLM_BASE_URL", "https://api.groq.com/openai/v1", "LLM_MODEL", "m"), "LLM_"));
    }

    @Test
    void validatorStripsThinkBeforeExtractingJson() {
        assertEquals("{\"a\":1}", LlmResponseValidator.stripFences("<think>{не json}</think>\n```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmResponseValidator.stripFences("рассуждение {x} </think>{\"a\":1}"));
    }
}
