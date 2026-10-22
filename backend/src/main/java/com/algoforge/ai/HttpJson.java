package com.algoforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON-over-HTTPS POST shared by the provider adapters, mapping failures to AiException kinds. */
final class HttpJson {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;

    HttpJson(ObjectMapper json) { this.json = json; }

    JsonNode post(String url, Map<String, String> headers, Object body, Duration timeout) throws AiException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            headers.forEach(b::header);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 429 || code == 408 || code == 529 || code >= 500) {
                throw new AiException(AiException.Kind.RETRYABLE, "HTTP " + code + ": " + snippet(res.body()));
            }
            if (code != 200) throw new AiException(AiException.Kind.FATAL, "HTTP " + code + ": " + snippet(res.body()));
            return json.readTree(res.body());
        } catch (HttpTimeoutException e) {
            throw new AiException(AiException.Kind.TIMEOUT, "request timed out");
        } catch (IOException e) {
            throw new AiException(AiException.Kind.RETRYABLE, "network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.FATAL, "interrupted");
        }
    }

    private static String snippet(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
