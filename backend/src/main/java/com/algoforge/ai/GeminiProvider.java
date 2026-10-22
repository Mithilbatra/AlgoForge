package com.algoforge.ai;

import com.algoforge.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Gemini generateContent with responseMimeType=application/json and responseJsonSchema. */
@Component
public class GeminiProvider implements AiProvider {
    static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final AppProperties.Ai cfg;
    private final HttpJson http;

    public GeminiProvider(AppProperties props, ObjectMapper json) {
        this.cfg = props.ai();
        this.http = new HttpJson(json);
    }

    @Override public String name() { return "gemini"; }

    @Override public boolean configured() { return cfg.geminiApiKey() != null && !cfg.geminiApiKey().isBlank(); }

    @Override
    public Completion complete(String model, String system, String user, JsonNode schema, int maxOutputTokens) throws AiException {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseJsonSchema", schema,
                        "maxOutputTokens", maxOutputTokens));
        String url = BASE + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";
        JsonNode res = http.post(url, Map.of("x-goog-api-key", cfg.geminiApiKey()), body, Duration.ofSeconds(cfg.timeoutSeconds()));
        JsonNode cand = res.path("candidates").path(0);
        String finish = cand.path("finishReason").asText();
        if ("MAX_TOKENS".equals(finish)) throw new AiException(AiException.Kind.INVALID_OUTPUT, "reply truncated at maxOutputTokens");
        if ("SAFETY".equals(finish) || "RECITATION".equals(finish) || res.path("promptFeedback").has("blockReason")) {
            throw new AiException(AiException.Kind.FATAL, "model declined the request (" + finish + ")");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : cand.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
        }
        if (text.isEmpty()) throw new AiException(AiException.Kind.INVALID_OUTPUT, "empty reply");
        JsonNode usage = res.path("usageMetadata");
        return new Completion(text.toString(), res.path("modelVersion").asText(model),
                usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null,
                usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null);
    }
}
