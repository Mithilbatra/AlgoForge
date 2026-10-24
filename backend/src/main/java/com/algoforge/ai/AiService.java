package com.algoforge.ai;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiOutputs.CoachReply;
import com.algoforge.ai.AiOutputs.Review;
import com.algoforge.ai.AiOutputs.TestProposal;
import com.algoforge.ai.AiOutputs.Twin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Provider-independent AI calls: model selection from ai_provider_configs, response cache keyed by the exact prompt,
 * per-user hourly limits, one retry on transient errors, and usage/error logging for every call.
 */
@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AppProperties.Ai cfg;
    private final Map<String, AiProvider> providers;

    public AiService(JdbcClient jdbc, ObjectMapper json, AppProperties props, List<AiProvider> providers) {
        this.jdbc = jdbc;
        this.json = json;
        this.cfg = props.ai();
        this.providers = providers.stream().collect(Collectors.toMap(AiProvider::name, Function.identity()));
    }

    public record ProviderStatus(String provider, boolean enabled, boolean configured, String model, List<String> roles,
                                 int maxOutputTokens) {}

    /** status: OK | CACHED | RATE_LIMITED | ERROR | TIMEOUT | INVALID_OUTPUT */
    public record Call<T>(String provider, String model, String status, T value, String error) {
        public boolean ok() { return value != null; }
    }

    interface Parser<T> { T parse(JsonNode n) throws AiOutputs.InvalidOutput; }

    public List<ProviderStatus> status() {
        return jdbc.sql("select provider, enabled, model, roles, max_output_tokens from ai_provider_configs order by provider")
                .query((rs, i) -> {
                    String name = rs.getString(1);
                    AiProvider p = providers.get(name);
                    return new ProviderStatus(name, rs.getBoolean(2), p != null && p.configured(), rs.getString(3),
                            List.of((String[]) rs.getArray(4).getArray()), rs.getInt(5));
                }).list();
    }

    public boolean dualMode() { return cfg.dualMode(); }

    /** Providers in ai_provider_configs order; the order only matters when dual mode is off. */
    List<ProviderStatus> available(String role) {
        return status().stream().filter(s -> s.enabled() && s.configured() && s.roles().contains(role)).toList();
    }

    public boolean anyAvailable(String role) { return !available(role).isEmpty(); }

    public List<Call<Review>> review(long userId, String userPrompt) {
        List<ProviderStatus> av = available("REVIEW");
        List<ProviderStatus> targets = cfg.dualMode() ? av : av.stream().limit(1).toList();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Call<Review>>> futures = targets.stream().map(p -> CompletableFuture.supplyAsync(() ->
                    call(p, "review", userId, Prompts.REVIEW_SYSTEM, userPrompt, Prompts.REVIEW_SCHEMA, AiOutputs::review), pool)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    public Optional<Call<List<TestProposal>>> proposeTests(long userId, String userPrompt) {
        return available("TESTGEN").stream().findFirst().map(p ->
                call(p, "testgen", userId, Prompts.TESTGEN_SYSTEM, userPrompt, Prompts.TESTGEN_SCHEMA, AiOutputs::tests));
    }

    /** Thinking counts against the output limit, and a twin needs a lot of both (Gemini used 13k thinking tokens for a 3k reply). */
    static final int TWIN_OUTPUT_TOKENS = 32000;

    /** Falls through to the next provider when one is down or refuses (out of credit, bad key): a twin is a single request worth finishing. */
    public Optional<Call<Twin>> twin(long userId, String userPrompt) {
        Call<Twin> last = null;
        for (ProviderStatus s : available("TESTGEN")) {
            var p = new ProviderStatus(s.provider(), s.enabled(), s.configured(), s.model(), s.roles(), Math.max(s.maxOutputTokens(), TWIN_OUTPUT_TOKENS));
            last = call(p, "twin", userId, Prompts.TWIN_SYSTEM, userPrompt, Prompts.TWIN_SCHEMA, AiOutputs::twin);
            if (!"ERROR".equals(last.status()) && !"TIMEOUT".equals(last.status())) break;
        }
        return Optional.ofNullable(last);
    }

    public Optional<Call<CoachReply>> coach(long userId, String userPrompt) {
        return available("REVIEW").stream().findFirst().map(p ->
                call(p, "coach", userId, Prompts.COACH_SYSTEM, userPrompt, Prompts.COACH_SCHEMA, AiOutputs::coach));
    }

    <T> Call<T> call(ProviderStatus p, String task, long userId, String system, String user, JsonNode schema, Parser<T> parser) {
        AiProvider provider = providers.get(p.provider());
        String key = sha256(String.join("\u0000", p.provider(), p.model(), task, Prompts.VERSION, user));
        Optional<String> cached = jdbc.sql("select response::text from ai_cache where cache_key = ? and expires_at > now()")
                .param(key).query(String.class).optional();
        if (cached.isPresent()) {
            try {
                T v = parser.parse(json.readTree(cached.get()));
                usage(userId, p, task, "CACHED", null, null, null, null);
                return new Call<>(p.provider(), p.model(), "CACHED", v, null);
            } catch (Exception e) {
                jdbc.sql("delete from ai_cache where cache_key = ?").param(key).update();
            }
        }
        long recent = jdbc.sql("""
                select count(*) from ai_usage where user_id = ? and created_at > now() - interval '1 hour'
                   and status in ('OK', 'ERROR', 'TIMEOUT', 'INVALID_OUTPUT')""").param(userId).query(Long.class).single();
        if (recent >= cfg.requestsPerUserPerHour()) {
            usage(userId, p, task, "RATE_LIMITED", null, null, null, null);
            return new Call<>(p.provider(), p.model(), "RATE_LIMITED", null, "Hourly AI request limit reached; try again later");
        }
        for (int attempt = 1; ; attempt++) {
            long t0 = System.nanoTime();
            try {
                AiProvider.Completion c = provider.complete(p.model(), system, user, schema, p.maxOutputTokens());
                int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
                JsonNode node;
                T value;
                try {
                    node = json.readTree(c.text());
                    value = parser.parse(node);
                } catch (Exception e) {
                    usage(userId, p, task, "INVALID_OUTPUT", c.inputTokens(), c.outputTokens(), ms, e.getMessage());
                    return new Call<>(p.provider(), c.model(), "INVALID_OUTPUT", null, "The model reply did not match the expected format");
                }
                jdbc.sql("""
                        insert into ai_cache (cache_key, provider, model, task, response, expires_at)
                        values (?, ?, ?, ?, cast(? as jsonb), now() + make_interval(days => ?))
                        on conflict (cache_key) do update set response = excluded.response, expires_at = excluded.expires_at""")
                        .params(key, p.provider(), p.model(), task, node.toString(), cfg.cacheDays()).update();
                usage(userId, p, task, "OK", c.inputTokens(), c.outputTokens(), ms, null);
                return new Call<>(p.provider(), c.model(), "OK", value, null);
            } catch (AiException e) {
                int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
                boolean transientError = e.kind() == AiException.Kind.RETRYABLE || e.kind() == AiException.Kind.TIMEOUT;
                if (transientError && attempt < 2) {
                    sleep(2000);
                    continue;
                }
                String status = switch (e.kind()) {
                    case TIMEOUT -> "TIMEOUT";
                    case INVALID_OUTPUT -> "INVALID_OUTPUT";
                    default -> "ERROR";
                };
                log.warn("AI {} call to {} failed: {}", task, p.provider(), e.getMessage());
                usage(userId, p, task, status, null, null, ms, e.getMessage());
                return new Call<>(p.provider(), p.model(), status, null, p.provider() + " request failed (" + status.toLowerCase() + ")");
            }
        }
    }

    private void usage(long userId, ProviderStatus p, String task, String status, Integer in, Integer out, Integer ms, String error) {
        jdbc.sql("""
                insert into ai_usage (user_id, provider, model, task, status, input_tokens, output_tokens, latency_ms, error)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(userId, p.provider(), p.model(), task, status, in, out, ms,
                        error == null ? null : error.length() > 500 ? error.substring(0, 500) : error).update();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
