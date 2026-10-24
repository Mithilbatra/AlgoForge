package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiException;
import com.algoforge.ai.AiProvider;
import com.algoforge.ai.AiService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** AiService against the real cache/usage tables with scripted fake providers (no network). */
class AiServiceIntegrationTest extends IntegrationTest {
    static final String REVIEW = """
            {"summary": "fine", "approach": "prefix sums", "correctness_opinion": "LIKELY_CORRECT", "suspected_bugs": [],
             "time_complexity": "O(n)", "space_complexity": "O(n)", "complexity_explanation": "one pass", "fits_limits": "YES",
             "suggestions": []}""";

    /** Replies from a script: a String is returned as the model text, an AiException is thrown. */
    static final class Scripted implements AiProvider {
        final String name;
        final Deque<Object> script = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        Scripted(String name, Object... replies) {
            this.name = name;
            script.addAll(List.of(replies));
        }

        @Override public String name() { return name; }
        @Override public boolean configured() { return true; }

        @Override
        public Completion complete(String model, String system, String user, JsonNode schema, int maxOutputTokens) throws AiException {
            calls.incrementAndGet();
            Object next = script.isEmpty() ? REVIEW : script.poll();
            if (next instanceof AiException e) throw e;
            return new Completion((String) next, model, 100, 50);
        }
    }

    /** Provider keys come from ai_provider_configs, so the tests do not depend on which vendors are configured. */
    String first() { return providerNames().get(0); }
    String second() { return providerNames().get(1); }
    java.util.List<String> providerNames() {
        return jdbc.sql("select provider from ai_provider_configs order by provider").query(String.class).list();
    }

    AiService service(boolean dual, int perHour, AiProvider... providers) {
        var ai = new AppProperties.Ai("k", "k", dual, 5, perHour, 30);
        return new AiService(jdbc, json, new AppProperties(null, null, null, ai, null), List.of(providers));
    }

    @Test
    void cachesIdenticalRequestsAndLogsUsage() throws Exception {
        long uid = userId(register("cache"));
        var primary = new Scripted(first(), REVIEW);
        var svc = service(false, 20, primary, new Scripted(second()));
        String prompt = "prompt " + System.nanoTime();
        assertThat(svc.review(uid, prompt)).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("OK"));
        assertThat(svc.review(uid, prompt)).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("CACHED"));
        assertThat(primary.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from ai_usage where user_id = ? and status = 'OK' and input_tokens = 100").param(uid)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void malformedOutputIsRejectedNotTrusted() throws Exception {
        long uid = userId(register("bad"));
        var svc = service(false, 20, new Scripted(first(), "{\"summary\": \"missing everything\"}"));
        var call = svc.review(uid, "p " + System.nanoTime()).getFirst();
        assertThat(call.status()).isEqualTo("INVALID_OUTPUT");
        assertThat(call.value()).isNull();
        var notJson = service(false, 20, new Scripted(first(), "Sure! Here is my review: looks good")).review(uid, "q " + System.nanoTime()).getFirst();
        assertThat(notJson.status()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void twinsFallBackToTheNextProviderWhenOneRefuses() throws Exception {
        long uid = userId(register("fallback"));
        var broke = new Scripted(first(), new AiException(AiException.Kind.FATAL, "HTTP 400: credit balance is too low"));
        var backup = new Scripted(second(), "{\"title\": \"missing the rest\"}");
        var call = service(false, 20, broke, backup).twin(uid, "twin " + System.nanoTime()).orElseThrow();
        assertThat(call.provider()).isEqualTo(second());
        assertThat(call.status()).isEqualTo("INVALID_OUTPUT");  // the second provider answered, so its result is reported
        assertThat(broke.calls.get()).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetriedOnceThenReported() throws Exception {
        long uid = userId(register("retry"));
        var flaky = new Scripted(first(), new AiException(AiException.Kind.RETRYABLE, "HTTP 529"), REVIEW);
        assertThat(service(false, 20, flaky).review(uid, "r " + System.nanoTime()).getFirst().status()).isEqualTo("OK");
        assertThat(flaky.calls.get()).isEqualTo(2);

        var down = new Scripted(first(), new AiException(AiException.Kind.TIMEOUT, "timeout"), new AiException(AiException.Kind.TIMEOUT, "timeout"));
        var call = service(false, 20, down).review(uid, "t " + System.nanoTime()).getFirst();
        assertThat(call.status()).isEqualTo("TIMEOUT");
        assertThat(down.calls.get()).isEqualTo(2);

        var refused = new Scripted(first(), new AiException(AiException.Kind.FATAL, "HTTP 400"));
        assertThat(service(false, 20, refused).review(uid, "f " + System.nanoTime()).getFirst().status()).isEqualTo("ERROR");
        assertThat(refused.calls.get()).isEqualTo(1);
    }

    @Test
    void perUserHourlyLimitStopsCalls() throws Exception {
        long uid = userId(register("limit"));
        var primary = new Scripted(first());
        var svc = service(false, 2, primary);
        svc.review(uid, "a " + System.nanoTime());
        svc.review(uid, "b " + System.nanoTime());
        assertThat(svc.review(uid, "c " + System.nanoTime()).getFirst().status()).isEqualTo("RATE_LIMITED");
        assertThat(primary.calls.get()).isEqualTo(2);
    }

    @Test
    void dualModeAsksBothProvidersAndSingleModeOnlyOne() throws Exception {
        long uid = userId(register("dual"));
        var c = new Scripted(first());
        var g = new Scripted(second());
        assertThat(service(true, 20, c, g).review(uid, "d " + System.nanoTime())).extracting(AiService.Call::provider)
                .containsExactlyInAnyOrder(first(), second());
        var c2 = new Scripted(first());
        var g2 = new Scripted(second());
        assertThat(service(false, 20, c2, g2).review(uid, "s " + System.nanoTime())).extracting(AiService.Call::provider).containsExactly(first());
        assertThat(g2.calls.get()).isZero();
    }
}
