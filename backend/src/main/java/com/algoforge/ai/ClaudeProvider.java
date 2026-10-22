package com.algoforge.ai;

import com.algoforge.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Anthropic Messages API with structured output (output_config.format = json_schema). */
@Component
public class ClaudeProvider implements AiProvider {
    static final String URL = "https://api.anthropic.com/v1/messages";
    static final String API_VERSION = "2026-10-21";
    // Models that accept server-side refusal fallbacks ("default" routing); others are sent without them.
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private final AppProperties.Ai cfg;
    private final HttpJson http;

    public ClaudeProvider(AppProperties props, ObjectMapper json) {
        this.cfg = props.ai();
        this.http = new HttpJson(json);
    }

    @Override public String name() { return "claude"; }

    @Override public boolean configured() { return cfg.claudeApiKey() != null && !cfg.claudeApiKey().isBlank(); }

    @Override
    public Completion complete(String model, String system, String user, JsonNode schema, int maxOutputTokens) throws AiException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        body.put("output_config", Map.of("effort", "high", "format", Map.of("type", "json_schema", "schema", schema)));
        Map<String, String> headers = new LinkedHashMap<>(Map.of("x-api-key", cfg.claudeApiKey(), "anthropic-version", API_VERSION));
        if (FALLBACK_MODELS.contains(model)) {
            body.put("fallbacks", "default");
            headers.put("anthropic-beta", "server-side-fallback-2026-07-01");
        }
        JsonNode res = http.post(URL, headers, body, Duration.ofSeconds(cfg.timeoutSeconds()));
        String stop = res.path("stop_reason").asText();
        if ("refusal".equals(stop)) throw new AiException(AiException.Kind.FATAL, "model declined the request");
        if ("max_tokens".equals(stop)) throw new AiException(AiException.Kind.INVALID_OUTPUT, "reply truncated at max_tokens");
        String text = null;
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").asText())) { text = block.path("text").asText(); break; }
        }
        if (text == null) throw new AiException(AiException.Kind.INVALID_OUTPUT, "no text block in reply");
        JsonNode usage = res.path("usage");
        return new Completion(text, res.path("model").asText(model),
                usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null,
                usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null);
    }
}
